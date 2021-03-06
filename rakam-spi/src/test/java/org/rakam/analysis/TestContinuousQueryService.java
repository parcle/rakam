package org.rakam.analysis;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.plugin.ContinuousQuery;
import org.rakam.report.QueryResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

public abstract class TestContinuousQueryService {
    private static final String PROJECT_NAME = TestContinuousQueryService.class.getName().replace(".", "_").toLowerCase().toLowerCase();

    public abstract ContinuousQueryService getContinuousQueryService();
    public abstract Metastore getMetastore();

    @BeforeMethod
    public void beforeMethod() throws Exception {
        getMetastore().createProject(PROJECT_NAME);
        getMetastore().getOrCreateCollectionFieldList(PROJECT_NAME, "test", ImmutableSet.of(new SchemaField("test", FieldType.LONG)));
    }

    @AfterMethod
    public void afterMethod() throws Exception {
        getContinuousQueryService().delete(PROJECT_NAME, "streamtest").join();
        getMetastore().deleteProject(PROJECT_NAME);
    }

    @Test
    public void testCreate() {
        ContinuousQuery report = new ContinuousQuery("streamtest", "name", "select count(*) as count from test",
                ImmutableList.of(), ImmutableMap.of());
        QueryResult join = getContinuousQueryService().create(PROJECT_NAME, report, false).getResult().join();

        assertFalse(join.isFailed());
        assertEquals(getContinuousQueryService().get(PROJECT_NAME, "streamtest"), report);
    }

    @Test
    public void testSchema() throws Exception {
        ContinuousQuery report = new ContinuousQuery("streamtest", "name", "select count(*) as count from test",
                ImmutableList.of(), ImmutableMap.of());

        QueryResult join = getContinuousQueryService().create(PROJECT_NAME, report, false).getResult().join();
        assertFalse(join.isFailed());

        assertEquals(getContinuousQueryService().getSchemas(PROJECT_NAME),
                ImmutableMap.of("streamtest", ImmutableList.of(new SchemaField("count", FieldType.LONG))));
    }
}
