package org.jboss.set.mavendependencyupdater;

import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.version.Version;
import org.jboss.logging.Logger;
import org.jboss.set.mavendependencyupdater.common.ident.ScopedArtifactRef;
import org.jboss.set.mavendependencyupdater.configuration.Configuration;
import org.jboss.set.mavendependencyupdater.loggerclient.ComponentUpgradeDTO;
import org.jboss.set.mavendependencyupdater.loggerclient.LoggerClient;
import org.jboss.set.mavendependencyupdater.loggerclient.LoggerClientFactory;
import org.jboss.set.mavendependencyupdater.loggerclient.UpgradeNotFoundException;
import org.jboss.set.mavendependencyupdater.rules.NeverRestriction;
import org.jboss.set.mavendependencyupdater.rules.Restriction;
import org.jboss.set.mavendependencyupdater.rules.TokenizedVersion;
import org.jboss.set.mavendependencyupdater.rules.VersionPrefixRestriction;
import org.jboss.set.mavendependencyupdater.rules.VersionStreamRestriction;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DependencyEvaluator {

    private static final Logger LOG = Logger.getLogger(DependencyEvaluator.class);

    private static final VersionStreamRestriction DEFAULT_STREAM_RESTRICTION =
            new VersionStreamRestriction(VersionStream.MICRO);

    final private Configuration configuration;
    final private AvailableVersionsResolver availableVersionsResolver;
    private LoggerClient loggerClient;
    private String projectCode;
    private boolean configUpToDate;

    public DependencyEvaluator(Configuration configuration, AvailableVersionsResolver availableVersionsResolver) {
        this.configuration = configuration;
        this.availableVersionsResolver = availableVersionsResolver;

        if (configuration.getLogger().isActive()) {
            loggerClient = LoggerClientFactory.createClient(URI.create(configuration.getLogger().getUri()));
            projectCode = configuration.getLogger().getProjectCode();
            LOG.infof("Logger service URI is %s", configuration.getLogger().getUri());
            LOG.infof("Logger project code is %s", configuration.getLogger().getProjectCode());
        } else {
            LOG.infof("Logger service not configured.");
        }
    }

    /**
     * Determine which artifacts can be upgraded.
     *
     * @return returns map "G:A" => Pair[newVersion, repoUrl]
     */
    public List<ComponentUpgrade> getVersionsToUpgrade(Collection<ScopedArtifactRef> dependencies) {
        List<ComponentUpgrade> versionsToUpgrade = new ArrayList<>();
        configUpToDate = true;

        for (ScopedArtifactRef dep : dependencies) {
            Artifact rangeArtifact = toVersionRangeArtifact(dep);

            if (dep.getVersionString().startsWith("$")) {
                LOG.warnf("Skipping dependency '%s', should this be resolved?", dep);
                continue;
            }

            if (configuration.getIgnoreScopes().contains(dep.getScope())) {
                LOG.debugf("Skipping dependency '%s', scope '%s' is ignored", dep, dep.getScope());
                continue;
            }

            try {
                List<Restriction> restrictions =
                        configuration.getRestrictionsFor(dep.getGroupId(), dep.getArtifactId());

                VersionRangeResult versionRangeResult = availableVersionsResolver.resolveVersionRange(rangeArtifact);
                Optional<Version> latest =
                        findLatest(dep, restrictions, versionRangeResult.getVersions());

                LOG.debugf("Available versions for '%s': %s", dep, versionRangeResult);
                if (latest.isPresent() && !dep.getVersionString().equals(latest.get().toString())) {
                    String latestVersion = latest.get().toString();
                    String repoId = versionRangeResult.getRepository(latest.get()).getId();
                    LOG.infof("Found possible upgrade of '%s' to '%s' in repo '%s'", dep, latestVersion, repoId);

                    // check when this upgrade was first detected
                    LocalDateTime firstSeen = findComponentUpgradeDate(dep, latestVersion);
                    versionsToUpgrade.add(new ComponentUpgrade(dep, latestVersion, repoId, firstSeen));
                } else {
                    LOG.debugf("  => no change");
                }
            } catch (RepositoryException e) {
                LOG.errorf("Could not resolve '%s'", rangeArtifact.toString());
            }
        }

        if (!configUpToDate) {
            LOG.warn("Configuration not up to date. Check the warnings above.");
        }

        sendDetectedUpgradesToExternalService(versionsToUpgrade);

        return versionsToUpgrade;
    }

    private static Artifact toVersionRangeArtifact(ArtifactRef ref) {
        return new DefaultArtifact(ref.getGroupId(), ref.getArtifactId(), null, "[" + ref.getVersionString() + ",)");
    }


    /**
     * Searches given list of available versions for the latest version in given stream.
     *
     * @param dependency        original dependency.
     * @param restrictions      list of restrictions that versions must satisfy.
     *                          If `VersionPrefixRestriction` is present, `stream` parameter is ignored.
     * @param availableVersions available versions.
     * @return latest available version in given stream.
     */
    Optional<Version> findLatest(ScopedArtifactRef dependency,
                                               List<Restriction> restrictions,
                                               List<Version> availableVersions) {
        if (restrictions.stream().anyMatch(r -> r instanceof NeverRestriction)) {
            return Optional.empty(); // blacklisted component
        }

        Optional<Restriction> prefixRestrictionOptional =
                restrictions.stream().filter(r -> r instanceof VersionPrefixRestriction).findFirst();
        boolean restrictedPrefix = prefixRestrictionOptional.isPresent();
        boolean restrictedStream = restrictions.stream().anyMatch(r -> r instanceof VersionStreamRestriction);

        if (restrictedPrefix) {
            VersionPrefixRestriction prefixRestriction = (VersionPrefixRestriction) prefixRestrictionOptional.get();
            if (!prefixRestriction.applies(dependency.getVersionString(), dependency.getVersionString())) {
                // configured version prefix doesn't match existing dependency => configuration needs to be updated
                LOG.warnf("Existing dependency '%s' doesn't match configured prefix: '%s'",
                        dependency, prefixRestriction.getPrefixString());
                configUpToDate = false;
                return Optional.empty();
            }
        }

        Stream<Version> workingStream = availableVersions.stream();

        // if neither stream nor prefix restrictions were given, use MICRO stream by default
        if (!restrictedPrefix && !restrictedStream) {
            workingStream = workingStream.filter(v ->
                    DEFAULT_STREAM_RESTRICTION.applies(v.toString(), dependency.getVersionString()));
        }

        // apply configured restrictions
        for (Restriction restriction : restrictions) {
            workingStream = workingStream.filter(v -> restriction.applies(v.toString(), dependency.getVersionString()));
        }

        return workingStream.max(Comparator.comparing(v -> TokenizedVersion.parse(v.toString())));
    }

    LocalDateTime findComponentUpgradeDate(ScopedArtifactRef dep, String newVersion) {
        if (loggerClient != null) {
            try {
                ComponentUpgradeDTO componentUpgrade = loggerClient.getFirst(projectCode, dep.getGroupId(), dep.getArtifactId(), newVersion);
                if (componentUpgrade != null) {
                    LOG.infof("Component upgrade to %s:%s:%s already seen at %s", dep.getGroupId(), dep.getArtifactId(), newVersion, componentUpgrade.created);
                    return componentUpgrade.created;
                }
            } catch (UpgradeNotFoundException e) {
                LOG.infof("Component upgrade to %s:%s:%s was not previously recorded", dep.getGroupId(), dep.getArtifactId(), newVersion);
            }
        }
        return null;
    }

    void sendDetectedUpgradesToExternalService(List<ComponentUpgrade> componentUpgrades) {
        if (loggerClient != null) {
            LOG.infof("Recording %d component upgrades under project '%s'", componentUpgrades.size(), projectCode);
            List<ComponentUpgradeDTO> dtos = componentUpgrades.stream()
                    .map(u -> convertToDTO(projectCode, u))
                    .collect(Collectors.toList());
            loggerClient.create(dtos);
        }
    }

    static ComponentUpgradeDTO convertToDTO(String projectCode, ComponentUpgrade componentUpgrade) {
        return new ComponentUpgradeDTO(projectCode,
                componentUpgrade.getArtifact().getGroupId(),
                componentUpgrade.getArtifact().getArtifactId(),
                componentUpgrade.artifact.getVersionString(),
                componentUpgrade.newVersion,
                null);
    }

    /**
     * Data bean wrapping component upgrade information.
     */
    public static class ComponentUpgrade {

        final private ArtifactRef artifact;
        final private String newVersion;
        final private String repository;
        final private LocalDateTime firstSeen;

        public ComponentUpgrade(ArtifactRef artifact, String newVersion, String repository, LocalDateTime firstSeen) {
            this.artifact = artifact;
            this.newVersion = newVersion;
            this.repository = repository;
            this.firstSeen = firstSeen;
        }

        public ComponentUpgrade(ArtifactRef artifact, String newVersion, String repository) {
            this(artifact, newVersion, repository, null);
        }

        public ArtifactRef getArtifact() {
            return artifact;
        }

        public String getNewVersion() {
            return newVersion;
        }

        public String getRepository() {
            return repository;
        }

        public LocalDateTime getFirstSeen() {
            return firstSeen;
        }

        @Override
        public String toString() {
            return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersionString()
                    + " -> " + newVersion;
        }
    }
}
