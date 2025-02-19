/********************************************************************************
 * Copyright (c) 2019 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import org.eclipse.openvsx.entities.ExtensionVersion;
import org.eclipse.openvsx.entities.FileResource;
import org.eclipse.openvsx.util.ArchiveUtil;
import org.eclipse.openvsx.util.ErrorResultException;
import org.eclipse.openvsx.util.LicenseDetection;
import org.elasticsearch.common.Strings;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

/**
 * Processes uploaded extension files and extracts their metadata.
 */
public class ExtensionProcessor implements AutoCloseable {

    private static final String PACKAGE_JSON = "extension/package.json";
    private static final String PACKAGE_NLS_JSON = "extension/package.nls.json";
    private static final String[] README = { "extension/README.md", "extension/README", "extension/README.txt" };
    private static final String[] LICENSE = { "extension/LICENSE.md", "extension/LICENSE", "extension/LICENSE.txt" };
    private static final String[] CHANGELOG = { "extension/CHANGELOG.md", "extension/CHANGELOG", "extension/CHANGELOG.txt" };

    private static final int MAX_CONTENT_SIZE = 512 * 1024 * 1024;
    private static final Pattern LICENSE_PATTERN = Pattern.compile("SEE( (?<license>\\S+))? LICENSE IN (?<file>\\S+)");

    private final PublishOptions publishOptions;
    private final InputStream inputStream;
    private byte[] content;
    private ZipFile zipFile;
    private JsonNode packageJson;
    private JsonNode packageNlsJson;

    public ExtensionProcessor(InputStream stream, PublishOptions publishOptions) {
        this.inputStream = stream;
        this.publishOptions = publishOptions;
    }

    @Override
    public void close() {
        if (zipFile != null) {
            try {
                zipFile.close();
            } catch (IOException exc) {
                throw new RuntimeException(exc);
            }
        }
    }

    private void readInputStream() {
        if (zipFile != null) {
            return;
        }
        try {
            if (inputStream != null)
                content = ByteStreams.toByteArray(inputStream);
            if (content.length > MAX_CONTENT_SIZE)
                throw new ErrorResultException("The extension package exceeds the size limit of 512 MB.", HttpStatus.PAYLOAD_TOO_LARGE);
            var tempFile = File.createTempFile("extension_", ".vsix");
            Files.write(content, tempFile);
            zipFile = new ZipFile(tempFile);
        } catch (ZipException exc) {
            throw new ErrorResultException("Could not read zip file: " + exc.getMessage());
        } catch (EOFException exc) {
            throw new ErrorResultException("Could not read from input stream: " + exc.getMessage());
        } catch (IOException exc) {
            throw new RuntimeException(exc);
        }
    }

    private void loadPackageJson() {
        if (packageJson != null) {
            return;
        }
        readInputStream();

        // Read package.json
        var bytes = ArchiveUtil.readEntry(zipFile, PACKAGE_JSON);
        if (bytes == null)
            throw new ErrorResultException("Entry not found: " + PACKAGE_JSON);
        try {
            var mapper = new ObjectMapper();
            packageJson = mapper.readTree(bytes);
        } catch (JsonParseException exc) {
            throw new ErrorResultException("Invalid JSON format in " + PACKAGE_JSON
                    + ": " + exc.getMessage());
        } catch (IOException exc) {
            throw new RuntimeException(exc);
        }

        // Read package.nls.json
        bytes = ArchiveUtil.readEntry(zipFile, PACKAGE_NLS_JSON);
        if (bytes != null) {
            try {
                var mapper = new ObjectMapper();
                packageNlsJson = mapper.readTree(bytes);
            } catch (JsonParseException exc) {
                throw new ErrorResultException("Invalid JSON format in " + PACKAGE_NLS_JSON
                        + ": " + exc.getMessage());
            } catch (IOException exc) {
                throw new RuntimeException(exc);
            }
        }
    }

    public String getExtensionName() {
        loadPackageJson();
        return packageJson.path("name").asText();
    }

    public String getNamespace() {
        loadPackageJson();
        return packageJson.path("publisher").asText();
    }

    public List<String> getExtensionDependencies() {
        loadPackageJson();
        var result = getStringList(packageJson.path("extensionDependencies"));
        return result != null ? result : Collections.emptyList();
    }

    public List<String> getBundledExtensions() {
        loadPackageJson();
        var result = getStringList(packageJson.path("extensionPack"));
        return result != null ? result : Collections.emptyList();
    }

    public ExtensionVersion getMetadata() {
        loadPackageJson();
        var extension = new ExtensionVersion();
        extension.setVersion(packageJson.path("version").textValue());
        extension.setPreview(packageJson.path("preview").booleanValue());
        extension.setDisplayName(getNlsValue(packageJson.path("displayName")));
        extension.setDescription(getNlsValue(packageJson.path("description")));
        extension.setEngines(getEngines(packageJson.path("engines")));
        extension.setCategories(getStringList(packageJson.path("categories")));
        extension.setExtensionKind(getStringList(packageJson.path("extensionKind")));
        extension.setTags(getStringList(packageJson.path("keywords")));
        extension.setLicense(packageJson.path("license").textValue());
        extension.setHomepage(getUrl(packageJson.path("homepage")));
        extension.setRepository(getUrl(packageJson.path("repository")));
        extension.setBugs(getUrl(packageJson.path("bugs")));
        extension.setMarkdown(packageJson.path("markdown").textValue());
        var galleryBanner = packageJson.path("galleryBanner");
        if (galleryBanner.isObject()) {
            extension.setGalleryColor(galleryBanner.path("color").textValue());
            extension.setGalleryTheme(galleryBanner.path("theme").textValue());
        }
        extension.setQna(packageJson.path("qna").textValue());
        return extension;
    }

    private List<String> getStringList(JsonNode node) {
        if (node.isArray()) {
            var set = new LinkedHashSet<String>();
            for (var element : node) {
                if (element.isTextual())
                    set.add(element.textValue());
            }
            return new ArrayList<>(set);
        }
        return null;
    }

    private String getNlsValue(JsonNode node) {
        var value = node.textValue();
        if (packageNlsJson != null && value.length() > 2 && value.startsWith("%") && value.endsWith("%")) {
            var key = value.substring(1, value.length() - 1);
            return packageNlsJson.path(key).textValue();
        }
        return value;
    }

    private String getUrl(JsonNode node) {
        String result = null;
        if (node.isTextual())
            result = node.textValue();
        if (node.isObject())
            result = node.path("url").textValue();
        if (result != null && (result.isEmpty() || result.equals(".")))
            result = null;
        return result;
    }

    private List<String> getEngines(JsonNode node) {
        if (node.isObject()) {
            var result = new ArrayList<String>();
            var fieldIter = node.fields();
            while (fieldIter.hasNext()) {
                var entry = fieldIter.next();
                result.add(entry.getKey() + "@" + entry.getValue().textValue());
            }
            return result;
        }
        return null;
    }

    public List<FileResource> getResources(ExtensionVersion extension) {
        var resources = new ArrayList<FileResource>();
        var binary = getBinary(extension);
        if (binary != null)
            resources.add(binary);
        var manifest = getManifest(extension);
        if (manifest != null)
            resources.add(manifest);
        var readme = getReadme(extension);
        if (readme != null)
            resources.add(readme);
        var changelog = getChangelog(extension);
        if (changelog != null)
            resources.add(changelog);
        var license = getLicense(extension);
        if (license != null)
            resources.add(license);
        var icon = getIcon(extension);
        if (icon != null)
            resources.add(icon);
        if (extension.getExtensionKind() != null && extension.getExtensionKind().contains("web") && publishOptions.web)
            resources.addAll(getWebResources(extension));
        return resources;
    }

    protected List<FileResource> getWebResources(ExtensionVersion extension) {
        readInputStream();
        var namePrefix = "extension/";
        return zipFile.stream()
                .filter(zipEntry -> zipEntry.getName().startsWith(namePrefix))
                .map(zipEntry -> {
                    var bytes = ArchiveUtil.readEntry(zipFile, zipEntry);
                    if (bytes == null) {
                        return null;
                    }
                    var webResource = new FileResource();
                    webResource.setExtension(extension);
                    webResource.setName(zipEntry.getName());
                    webResource.setType(FileResource.WEB_RESOURCE);
                    webResource.setContent(bytes);
                    return webResource;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    protected FileResource getBinary(ExtensionVersion extension) {
        var binary = new FileResource();
        binary.setExtension(extension);
        binary.setType(FileResource.DOWNLOAD);
        binary.setContent(content);
        return binary;
    }

    protected FileResource getManifest(ExtensionVersion extension) {
        readInputStream();
        var bytes = ArchiveUtil.readEntry(zipFile, PACKAGE_JSON);
        if (bytes == null) {
            return null;
        }
        var manifest = new FileResource();
        manifest.setExtension(extension);
        manifest.setName("package.json");
        manifest.setType(FileResource.MANIFEST);
        manifest.setContent(bytes);
        return manifest;
    }

    protected FileResource getReadme(ExtensionVersion extension) {
        readInputStream();
        var result = readFromAlternateNames(README);
        if (result == null) {
            return null;
        }
        var readme = new FileResource();
        readme.setExtension(extension);
        readme.setName(result.getSecond());
        readme.setType(FileResource.README);
        readme.setContent(result.getFirst());
        return readme;
    }

    public FileResource getChangelog(ExtensionVersion extension) {
        readInputStream();
        var result = readFromAlternateNames(CHANGELOG);
        if (result == null) {
            return null;
        }
        var changelog = new FileResource();
        changelog.setExtension(extension);
        changelog.setName(result.getSecond());
        changelog.setType(FileResource.CHANGELOG);
        changelog.setContent(result.getFirst());
        return changelog;
    }

    protected FileResource getLicense(ExtensionVersion extension) {
        readInputStream();
        var license = new FileResource();
        license.setExtension(extension);
        license.setType(FileResource.LICENSE);
        // Parse specifications in the form "SEE MIT LICENSE IN LICENSE.txt"
        if (!Strings.isNullOrEmpty(extension.getLicense())) {
            var matcher = LICENSE_PATTERN.matcher(extension.getLicense());
            if (matcher.find()) {
                extension.setLicense(matcher.group("license"));
                var fileName = matcher.group("file");
                var bytes = ArchiveUtil.readEntry(zipFile, "extension/" + fileName);
                if (bytes != null) {
                    var lastSegmentIndex = fileName.lastIndexOf('/');
                    var lastSegment = fileName.substring(lastSegmentIndex + 1);
                    license.setName(lastSegment);
                    license.setContent(bytes);
                    detectLicense(bytes, extension);
                    return license;
                }
            }
        }

        var result = readFromAlternateNames(LICENSE);
        if (result == null) {
            return null;
        }
        license.setName(result.getSecond());
        license.setContent(result.getFirst());
        detectLicense(result.getFirst(), extension);
        return license;
    }

    private void detectLicense(byte[] content, ExtensionVersion extension) {
        if (Strings.isNullOrEmpty(extension.getLicense())) {
            var detection = new LicenseDetection();
            extension.setLicense(detection.detectLicense(content));
        }
    }

    private Pair<byte[], String> readFromAlternateNames(String[] names) {
        for (var name : names) {
            var entry = ArchiveUtil.getEntryIgnoreCase(zipFile, name);
            if (entry != null) {
                var bytes = ArchiveUtil.readEntry(zipFile, entry);
                var lastSegmentIndex = entry.getName().lastIndexOf('/');
                var lastSegment = entry.getName().substring(lastSegmentIndex + 1);
                return Pair.of(bytes, lastSegment);
            }
        }
        return null;
    }

    protected FileResource getIcon(ExtensionVersion extension) {
        loadPackageJson();
        var iconPath = packageJson.get("icon");
        if (iconPath == null || !iconPath.isTextual())
            return null;
        var iconPathStr = iconPath.asText().replace('\\', '/');
        var bytes = ArchiveUtil.readEntry(zipFile, "extension/" + iconPathStr);
        if (bytes == null)
            return null;
        var icon = new FileResource();
        icon.setExtension(extension);
        var fileNameIndex = iconPathStr.lastIndexOf('/');
        if (fileNameIndex >= 0)
            icon.setName(iconPathStr.substring(fileNameIndex + 1));
        else
            icon.setName(iconPathStr);
        icon.setType(FileResource.ICON);
        icon.setContent(bytes);
        return icon;
    }

}