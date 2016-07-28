/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire.java;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.io.Closer;
import com.squareup.wire.java.internal.ProfileFileElement;
import com.squareup.wire.java.internal.ProfileParser;
import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.ProtoFile;
import com.squareup.wire.schema.Schema;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import okio.Okio;
import okio.Source;

import static com.google.common.collect.Iterables.getOnlyElement;

/**
 * Load files with the {@code .wire} suffix for a schema. The file name is the profile name; for
 * example the {@code android} profile loads {@code android.wire} files.
 */
public final class ProfileLoader {
  private final String name;
  private final Set<Location> protoLocations = new LinkedHashSet<>();

  public ProfileLoader(String name) {
    this.name = name;
  }

  public ProfileLoader addSchema(Schema schema) {
    for (ProtoFile protoFile : schema.protoFiles()) {
      protoLocations.add(protoFile.location());
    }
    return this;
  }

  public ProfileLoader addProtoLocation(Location location) {
    protoLocations.add(location);
    return this;
  }

  public Profile load() throws IOException {
    Multimap<Path, String> pathsToAttempt = pathsToAttempt();

    ImmutableList.Builder<ProfileFileElement> result = ImmutableList.builder();
    try (Closer closer = Closer.create()) {
      for (Map.Entry<Path, Collection<String>> entry : pathsToAttempt.asMap().entrySet()) {
        Path base = entry.getKey();
        if (Files.isRegularFile(base)) {
          FileSystem sourceFs = FileSystems.newFileSystem(base, getClass().getClassLoader());
          closer.register(sourceFs);
          base = getOnlyElement(sourceFs.getRootDirectories());
        }
        for (String path : entry.getValue()) {
          ProfileFileElement element = loadProfileFile(base, path);
          if (element != null) result.add(element);
        }
      }
    }

    // TODO(jwilson): validate type references and imports.
    return new Profile(result.build());
  }

  /**
   * Returns a multimap whose keys are base directories and whose values are potential locations of
   * wire profile files.
   */
  Multimap<Path, String> pathsToAttempt() {
    Multimap<Path, String> result = MultimapBuilder.linkedHashKeys().linkedHashSetValues().build();
    for (Location location : protoLocations) {
      pathsToAttempt(result, location);
    }
    return result;
  }

  /**
   * Computes all possible {@code .wire} profile files for the {@code .proto} at {@code location}
   * and adds them to {@code result}.
   */
  void pathsToAttempt(Multimap<Path, String> sink, Location location) {
    Path base = Paths.get(location.base());

    String path = location.path();
    while (!path.isEmpty()) {
      String parent = path.substring(0, path.lastIndexOf('/', path.length() - 2) + 1);
      String profilePath = parent + name + ".wire";
      sink.put(base, profilePath);
      path = parent;
    }
  }

  /**
   * Parses the {@code .wire} file at {@code base/path} and returns it. Returns null if no such
   * file exists.
   */
  private ProfileFileElement loadProfileFile(Path base, String path) throws IOException {
    Source source = source(base, path);
    if (source == null) return null;
    try {
      Location location = Location.get(base.toString(), path);
      String data = Okio.buffer(source).readUtf8();
      return new ProfileParser(location, data).read();
    } catch (IOException e) {
      throw new IOException("Failed to load " + source + " from " + base, e);
    } finally {
      source.close();
    }
  }

  private static Source source(Path base, String path) throws IOException {
    Path resolvedPath = base.resolve(path);
    if (Files.exists(resolvedPath)) {
      return Okio.source(resolvedPath);
    }
    return null;
  }
}
