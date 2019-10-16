package org.jboss.set.mavendependencyupdater.configuration;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import org.jboss.set.mavendependencyupdater.rules.IgnoreRestriction;
import org.jboss.set.mavendependencyupdater.rules.NeverRestriction;
import org.jboss.set.mavendependencyupdater.rules.QualifierRestriction;
import org.jboss.set.mavendependencyupdater.rules.Restriction;
import org.jboss.set.mavendependencyupdater.rules.VersionPrefixRestriction;
import org.jboss.set.mavendependencyupdater.rules.VersionStreamRestriction;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ConfigurationTestCase {

    private Configuration config;

    @Before
    public void setUp() throws URISyntaxException, IOException {
        URL resource = getClass().getClassLoader().getResource("configuration.json");
        Assert.assertNotNull(resource);
        config = new Configuration(new File(resource.toURI()));
    }

    @Test
    public void testGitHubConfig() {
        Assert.assertEquals("TomasHofman/wildfly", config.getGitHub().getOriginRepository());
        Assert.assertEquals("wildfly/wildfly", config.getGitHub().getUpstreamRepository());
        Assert.assertEquals("master", config.getGitHub().getUpstreamBaseBranch());
        Assert.assertEquals("joe", config.getGitHub().getLogin());
        Assert.assertEquals("1234abcd", config.getGitHub().getAccessToken());
    }

    @Test
    public void testGitConfig() {
        Assert.assertEquals("origin", config.getGit().getRemote());
        Assert.assertEquals("master", config.getGit().getBaseBranch());
    }

    @Test
    public void testIgnoreScopes() {
        Assert.assertTrue(config.getIgnoreScopes().contains("test"));
    }

    @Test
    public void testRestrictions() {
        List<Restriction> restrictions = config.getRestrictionsFor("org.picketlink", "picketlink-config");
        Assert.assertEquals(2, restrictions.size());
        Assert.assertTrue(restrictions.get(0) instanceof VersionStreamRestriction);
        Assert.assertTrue(restrictions.get(1) instanceof QualifierRestriction);
        Assert.assertTrue(restrictions.get(1).applies("1.Final", null));
        Assert.assertTrue(restrictions.get(1).applies("1.SP02", null));
        Assert.assertFalse(restrictions.get(1).applies("1.Beta1", null));

        restrictions = config.getRestrictionsFor("org.apache.cxf.xjc-utils", "whatever");
        Assert.assertEquals(1, restrictions.size());
        Assert.assertTrue(restrictions.get(0) instanceof IgnoreRestriction);
        Assert.assertFalse(restrictions.get(0).applies("1.2.3.fuse-1234-redhat-00001", null));

        restrictions = config.getRestrictionsFor("org.wildfly", "wildfly-core");
        Assert.assertEquals(2, restrictions.size());
        Assert.assertTrue(restrictions.get(0) instanceof VersionPrefixRestriction);
        Assert.assertTrue(restrictions.get(0).applies("10.0.0", null));
        Assert.assertTrue(restrictions.get(0).applies("10.0.0.1", null));
        Assert.assertFalse(restrictions.get(0).applies("10.0.1", null));
        Assert.assertTrue(restrictions.get(1) instanceof QualifierRestriction);
        Assert.assertTrue(restrictions.get(1).applies("1.Beta1", null));
        Assert.assertFalse(restrictions.get(1).applies("1.Final", null));

        // defined by wildcard "org.jboss.*:*"
        Assert.assertTrue(config.getRestrictionFor("org.jboss.whatever", "abcd", NeverRestriction.class).isPresent());
        // *:*
        Assert.assertFalse(config.getRestrictionFor("org.jbosswhatever", "abcd", NeverRestriction.class).isPresent());
        Assert.assertTrue(config.getRestrictionFor("org.jbosswhatever", "abcd", QualifierRestriction.class).isPresent());
    }

    @Test
    public void testRepositories() {
        Map<String, String> repositories = config.getRepositories();
        Assert.assertEquals(2, repositories.size());
        Assert.assertEquals(repositories.get("MRRC GA"), "https://maven.repository.redhat.com/ga/");
        Assert.assertEquals(repositories.get("MRRC EA"), "https://maven.repository.redhat.com/earlyaccess/all/");
    }
}
