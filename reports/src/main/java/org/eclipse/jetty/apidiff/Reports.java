//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.apidiff;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.config.Options;
import japicmp.model.AccessModifier;
import japicmp.model.JApiClass;
import japicmp.output.xml.XmlOutput;
import japicmp.output.xml.XmlOutputGenerator;
import japicmp.output.xml.XmlOutputGeneratorOptions;
import japicmp.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Reports
{
    public static void main(String[] args)
    {
        Reports reports = new Reports();

        Path basedir = Paths.get(System.getProperty("user.dir")).getParent();

        Path jettyRoot9 = basedir.resolve("jetty-9.4").toAbsolutePath();
        Path jettyRoot10 = basedir.resolve("jetty-10.0").toAbsolutePath();
        Path jettyRoot11 = basedir.resolve("jetty-11.0").toAbsolutePath();

        // Compare 9.4 to 10.0
        reports.generateDiff(jettyRoot9, jettyRoot10, "target/jetty-9.4-to-10.0-diff.html");

        // Compare 10.0 to 11.0
        reports.generateDiff(jettyRoot10, jettyRoot11, "target/jetty-10.0-to-11.0-diff.html");
    }

    private void cleanup(Path text, Function<String, String> lineFunction) throws IOException
    {
        // copy text to new location
        Path copyOf = Paths.get("target/copy-of-" + text.getFileName());
        Files.move(text, copyOf, StandardCopyOption.REPLACE_EXISTING);

        // Create new file (on the fly)
        try (BufferedReader reader = Files.newBufferedReader(copyOf, UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(text, UTF_8))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                String result = lineFunction.apply(line);
                writer.write(result);
                writer.write("\n");
            }
        }
    }

    public Path generateDiff(Path jettyRootOld, Path jettyRootNew, String outputFilename)
    {
        try
        {
            if (!Files.exists(jettyRootOld))
                throw new FileNotFoundException("Unable to find OLD Root Path: " + jettyRootOld);
            if (!Files.exists(jettyRootNew))
                throw new FileNotFoundException("Unable to find NEW Root Path: " + jettyRootNew);

            Options options = Options.newDefault();
            options.setHtmlOutputFile(Optional.of(outputFilename));
            options.setIgnoreMissingClasses(true);
            options.setOutputOnlyModifications(true);
            options.setOutputOnlyBinaryIncompatibleModifications(false);
            options.setIgnoreMissingNewVersion(false);
            options.setIgnoreMissingOldVersion(false);
            options.setSemanticVersioning(false);
            options.setAccessModifier(AccessModifier.PROTECTED);
            options.setHtmlStylesheet(Optional.of("src/main/resources/report.css"));

            String oldVersion = loadVersion(jettyRootOld);
            String newVersion = loadVersion(jettyRootNew);

            options.setOldArchives(toList(jettyRootOld, oldVersion));
            options.setNewArchives(toList(jettyRootNew, newVersion));
            JarArchiveComparator jarArchiveComparator = new JarArchiveComparator(JarArchiveComparatorOptions.of(options));
            // example of how to tweak rules
            /*
            jarArchiveComparator.getJarArchiveComparatorOptions().addOverrideCompatibilityChange(
                new JarArchiveComparatorOptions.OverrideCompatibilityChange(
                    JApiCompatibilityChange.METHOD_REMOVED_IN_SUPERCLASS,
                    true, true, JApiSemanticVersionLevel.MAJOR
                )
            );
             */
            List<JApiClass> jApiClasses = jarArchiveComparator.compare(options.getOldArchives(), options.getNewArchives());

            Path htmlOutputPath = Paths.get(outputFilename);
            System.out.printf("## Generating report: %s%n", htmlOutputPath);

            XmlOutputGeneratorOptions xmlOutputGeneratorOptions = new XmlOutputGeneratorOptions();
            xmlOutputGeneratorOptions.setCreateSchemaFile(true);
            xmlOutputGeneratorOptions.setTitle("Changes in Eclipse Jetty APIs from " + oldVersion + " to " + newVersion);
            XmlOutputGenerator xmlGenerator = new XmlOutputGenerator(jApiClasses, options, xmlOutputGeneratorOptions);
            try (XmlOutput xmlOutput = xmlGenerator.generate())
            {
                XmlOutputGenerator.writeToFiles(options, xmlOutput);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            System.out.printf("## Cleaning report: %s%n", htmlOutputPath);

            String rootOld = jettyRootOld.resolve("target/dependency/").toAbsolutePath().toString();
            String rootNew = jettyRootNew.resolve("target/dependency/").toAbsolutePath().toString();
            cleanup(htmlOutputPath, (line) ->
                line.replaceAll(rootOld, "\\${maven.repo}")
                    .replaceAll(rootNew, "\\${maven.repo}")
                    .replaceAll(".jar;", ".jar<br/>\n")
                    .replaceAll(".jar:", ".jar<br/>\n")
            );

            /*
            StdoutOutputGenerator stdoutOutputGenerator = new StdoutOutputGenerator(options, jApiClasses);
            String output = stdoutOutputGenerator.generate();
            System.out.println(output);
             */
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        return Paths.get(outputFilename);
    }

    private String loadVersion(Path root) throws IOException
    {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("target/classes/build.properties")))
        {
            props.load(in);
            return props.getProperty("jetty.version");
        }
    }

    private List<JApiCmpArchive> toList(final Path jettyRoot, final String version) throws IOException
    {
        List<JApiCmpArchive> archives = new ArrayList<>();

        Files.walkFileTree(jettyRoot.resolve("target/dependency"), Collections.emptySet(), 10, new FileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
            {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                if (!file.getFileName().toString().endsWith(".jar"))
                {
                    // skip
                    return FileVisitResult.CONTINUE;
                }

                if (file.toString().contains("org/eclipse/jetty"))
                {
                    if (!file.toString().contains("/toolchain/") && !file.toString().contains("/orbit/"))
                    {
                        archives.add(new JApiCmpArchive(file.toFile(), version));
                    }
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException
            {
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
            {
                return FileVisitResult.CONTINUE;
            }
        });

        Collections.sort(archives, new JApiCmpArchiveComparator());

        return archives;
    }
}
