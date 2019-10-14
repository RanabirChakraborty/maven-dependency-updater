package org.jboss.set.mavendependencyupdater.cli.upgradeprocessing;

import org.apache.commons.lang3.tuple.Pair;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jboss.logging.Logger;
import org.jboss.set.mavendependencyupdater.PomDependencyUpdater;
import org.jboss.set.mavendependencyupdater.git.GitRepository;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TextReportProcessingStrategy implements UpgradeProcessingStrategy {

    private static final Logger LOG = Logger.getLogger(TextReportProcessingStrategy.class);

    private File pomFile;
    private String outputFileName;
    private GitRepository gitRepository;
    private PatchDigestRecorder digestRecorder = new PatchDigestRecorder();

    public TextReportProcessingStrategy(File pomFile, String outputFileName) {
        this.pomFile = pomFile;
        this.outputFileName = outputFileName;
        File gitDir = new File(pomFile.getParent(), ".git");
        try {
            this.gitRepository = new GitRepository(gitDir, null);
        } catch (IOException e) {
            throw new RuntimeException("Failure when reading git repository: " + gitDir, e);
        }
    }

    @Override
    public boolean process(Map<ArtifactRef, String> upgrades) {
        PrintStream outputStream = null;
        try {
            List<Map.Entry<ArtifactRef, String>> sortedEntries = upgrades.entrySet().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey)).collect(Collectors.toList());

            if (sortedEntries.size() == 0) {
                LOG.info("No components to upgrade.");
                return true;
            }

            if (outputFileName != null) {
                outputStream = new PrintStream(outputFileName);
            } else {
                outputStream = System.out;
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss z yyyy-MM-dd");
            outputStream.println("Generated at " + formatter.format(ZonedDateTime.now()));
            outputStream.println();

            int counter = 0;
            for (Map.Entry<ArtifactRef, String> entry : sortedEntries) {
                ArtifactRef artifact = entry.getKey();
                String newVersion = entry.getValue();
                PomDependencyUpdater.upgradeDependencies(pomFile, Collections.singletonMap(artifact, newVersion));

                Pair<ArtifactRef, String> previous = digestRecorder.recordPatchDigest(pomFile, artifact, newVersion);
                gitRepository.resetLocalChanges();

                if (previous == null) {
                    counter++;
                    outputStream.println(String.format("%s:%s:%s -> %s",
                            artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersionString(), newVersion));
                }
            }

            outputStream.println("\n" + counter + " items");
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Report generation failed", e);
        } finally {
            try {
                gitRepository.resetLocalChanges();
            } catch (GitAPIException e) {
                LOG.error("Can't reset local changes", e);
            }

            if (outputStream != null && outputStream != System.out) {
                outputStream.close();
            }
        }
    }
}
