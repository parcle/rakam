package org.rakam.collection.mapper.geoip;


import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import com.maxmind.db.Reader;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.Country;
import io.airlift.log.Logger;
import org.apache.avro.generic.GenericRecord;
import org.rakam.collection.Event;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.collection.event.FieldDependencyBuilder;
import org.rakam.plugin.EventMapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static org.rakam.collection.mapper.geoip.GeoIPModuleConfig.SourceType.ip_field;
import static org.rakam.collection.mapper.geoip.GeoIPModuleConfig.SourceType.request_ip;


public class GeoIPEventMapper implements EventMapper {
    final static Logger LOGGER = Logger.get(GeoIPEventMapper.class);

    private final static List<String> ATTRIBUTES = ImmutableList.of("country","country_code","region","city","latitude","longitude","timezone");
    private final DatabaseReader countryLookup;
    private final String[] attributes;
    private final GeoIPModuleConfig config;

    public GeoIPEventMapper(GeoIPModuleConfig config) throws IOException {
        Preconditions.checkNotNull(config, "config is null");
        this.config = config;
        InputStream countryDatabase;
        if(config.getDatabase() != null) {
            countryDatabase = new FileInputStream(config.getDatabase());
        } else
        if(config.getDatabaseUrl() != null) {
            try {
                countryDatabase = new FileInputStream(downloadOrGetFile(config.getDatabaseUrl()));
            } catch (Exception e) {
                throw Throwables.propagate(e);
            }
        } else {
            throw new IllegalStateException();
        }

        countryLookup = new DatabaseReader.Builder(countryDatabase).fileMode(Reader.FileMode.MEMORY).build();
        if(config.getAttributes() != null) {
            for (String attr : config.getAttributes()) {
                if(!ATTRIBUTES.contains(attr)) {
                    throw new IllegalArgumentException("Attribute "+attr+" is not exist. Available attributes: " +
                            Joiner.on(", ").join(ATTRIBUTES));
                }
            }
            attributes = config.getAttributes().stream().toArray(String[]::new);
        } else {
            attributes = ATTRIBUTES.toArray(new String[ATTRIBUTES.size()]);
        }
    }

    @Override
    public void map(Event event, Iterable<Map.Entry<String, String>> extraProperties, InetAddress sourceAddress) {
        GenericRecord properties = event.properties();

        InetAddress address;
        if(config.getSource() == ip_field) {
            String ip = (String) properties.get("ip");
            if(ip == null) {
                return;
            }
            try {
                // it's slow because java performs reverse hostname lookup.
                address = Inet4Address.getByName(ip);
            } catch (UnknownHostException e) {
                return;
            }
        } else
        if(config.getSource() == request_ip) {
            address = sourceAddress;
        } else {
            throw new IllegalStateException("source is not supported");
        }

        CityResponse response;
        try {
            response = countryLookup.city(address);
        } catch (AddressNotFoundException e) {
            return;
        }catch (Exception e) {
            LOGGER.error(e, "Error while search for location information. ");
            return;
        }

//        countryLookup.isp()
//        countryLookup.connectionType()

        Country country = response.getCountry();

        for (String attribute : attributes) {
            switch (attribute) {
                case "country":
                    properties.put("country", country.getName());
                    break;
                case "country_code":
                    properties.put("country_code", country.getIsoCode());
                    break;
                case "region":
                    properties.put("region", response.getContinent().getName());
                    break;
                case "city":
                    properties.put("city", response.getCity().getName());
                    break;
                case "latitude":
                    properties.put("latitude", response.getLocation().getLatitude());
                    break;
                case "longitude":
                    properties.put("longitude", response.getLocation().getLongitude());
                    break;
                case "timezone":
                    properties.put("timezone", response.getLocation().getTimeZone());
                    break;
            }
        }
    }

    @Override
    public void addFieldDependency(FieldDependencyBuilder builder) {
        List<SchemaField> fields = Arrays.stream(attributes)
                .map(attr -> new SchemaField(attr, getType(attr), true))
                .collect(Collectors.toList());

        switch (config.getSource()) {
            case request_ip:
                builder.addFields(fields);
                break;
            case ip_field:
                builder.addFields("ip", fields);
                break;
            default:
                throw new IllegalStateException();
        }
    }

    private static FieldType getType(String attr) {
        switch (attr) {
            case "country":
            case "country_code":
            case "region":
            case "city":
            case "timezone":
                return FieldType.STRING;
            case "latitude":
            case "longitude":
                return FieldType.DOUBLE;
            default:
                throw new IllegalStateException();
        }
    }

    private File downloadOrGetFile(String fileUrl) throws Exception {
        URL url = new URL(fileUrl);
        String name = url.getFile().substring(url.getFile().lastIndexOf('/') + 1, url.getFile().length());
        File data = new File("/tmp/rakam/" + name);
        data.getParentFile().mkdirs();

        String extension = Files.getFileExtension(data.getAbsolutePath());
        if(extension.equals("gz")) {
            File extractedFile = new File("/tmp/rakam/" + Files.getNameWithoutExtension(data.getAbsolutePath()));
            if(extractedFile.exists()) {
                return extractedFile;
            }

            if (!data.exists()) {
                try {
                    new HttpDownloadHelper().download(url, data.toPath(), new HttpDownloadHelper.VerboseProgress(System.out));
                } catch (Exception e) {
                    throw Throwables.propagate(e);
                }
            }

            GZIPInputStream gzipInputStream =
                    new GZIPInputStream(new FileInputStream(data));

            FileOutputStream out = new FileOutputStream(extractedFile);

            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipInputStream.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }

            gzipInputStream.close();
            out.close();
            data.delete();

            return extractedFile;
        } else {
            if(data.exists()) {
                return data;
            }

            new HttpDownloadHelper().download(url, data.toPath(), new HttpDownloadHelper.VerboseProgress(System.out));

            return data;
        }
    }
}