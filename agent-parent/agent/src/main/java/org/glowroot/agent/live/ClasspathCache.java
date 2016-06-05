/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.live;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Splitter;
import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import com.google.common.io.Resources;
import org.immutables.value.Value;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.util.Reflections;
import org.glowroot.agent.weaving.AnalyzedWorld;
import org.glowroot.agent.weaving.ClassNames;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ACC_NATIVE;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ASM5;

// TODO remove items from classpathLocations and classNameLocations when class loaders are no longer
// present, e.g. in wildfly after undeploying an application
class ClasspathCache {

    private static final Logger logger = LoggerFactory.getLogger(ClasspathCache.class);

    private final AnalyzedWorld analyzedWorld;
    private final @Nullable Instrumentation instrumentation;

    @GuardedBy("this")
    private final Set<Location> classpathLocations = Sets.newHashSet();

    // using ImmutableMultimap because it is very space efficient
    // this is not updated often so trading space efficiency for copying the entire map on update
    @GuardedBy("this")
    private ImmutableMultimap<String, Location> classNameLocations = ImmutableMultimap.of();

    ClasspathCache(AnalyzedWorld analyzedWorld, @Nullable Instrumentation instrumentation) {
        this.analyzedWorld = analyzedWorld;
        this.instrumentation = instrumentation;
    }

    // using synchronization instead of concurrent structures in this cache to conserve memory
    synchronized ImmutableList<String> getMatchingClassNames(String partialClassName, int limit) {
        // update cache before proceeding
        updateCache();
        PartialClassNameMatcher matcher = new PartialClassNameMatcher(partialClassName);
        Set<String> fullMatchingClassNames = Sets.newLinkedHashSet();
        Set<String> matchingClassNames = Sets.newLinkedHashSet();
        // also check loaded classes, e.g. for groovy classes
        Iterator<String> i = classNameLocations.keySet().iterator();
        if (instrumentation != null) {
            List<String> loadedClassNames = Lists.newArrayList();
            for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                if (!clazz.getName().startsWith("[")) {
                    loadedClassNames.add(clazz.getName());
                }
            }
            i = Iterators.concat(i, loadedClassNames.iterator());
        }
        while (i.hasNext()) {
            String className = i.next();
            String classNameUpper = className.toUpperCase(Locale.ENGLISH);
            boolean potentialFullMatch = matcher.isPotentialFullMatch(classNameUpper);
            if (matchingClassNames.size() == limit && !potentialFullMatch) {
                // once limit reached, only consider full matches
                continue;
            }
            if (fullMatchingClassNames.size() == limit) {
                break;
            }
            if (matcher.isPotentialMatch(classNameUpper)) {
                if (potentialFullMatch) {
                    fullMatchingClassNames.add(className);
                } else {
                    matchingClassNames.add(className);
                }
            }
        }
        return combineClassNamesWithLimit(fullMatchingClassNames, matchingClassNames, limit);
    }

    // using synchronization over concurrent structures in this cache to conserve memory
    synchronized ImmutableList<UiAnalyzedMethod> getAnalyzedMethods(String className) {
        // update cache before proceeding
        updateCache();
        Set<UiAnalyzedMethod> analyzedMethods = Sets.newHashSet();
        Collection<Location> locations = classNameLocations.get(className);
        for (Location location : locations) {
            try {
                analyzedMethods.addAll(getAnalyzedMethods(location, className));
            } catch (IOException e) {
                logger.warn(e.getMessage(), e);
            }
        }
        if (instrumentation != null) {
            // also check loaded classes, e.g. for groovy classes
            for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                if (clazz.getName().equals(className)) {
                    analyzedMethods.addAll(getAnalyzedMethods(clazz));
                }
            }
        }
        return ImmutableList.copyOf(analyzedMethods);
    }

    // using synchronization over concurrent structures in this cache to conserve memory
    synchronized void updateCache() {
        Multimap<String, Location> newClassNameLocations = HashMultimap.create();
        for (ClassLoader loader : getKnownClassLoaders()) {
            updateCache(loader, newClassNameLocations);
        }
        updateCacheWithClasspathClasses(newClassNameLocations);
        updateCacheWithBootstrapClasses(newClassNameLocations);
        if (!newClassNameLocations.isEmpty()) {
            Multimap<String, Location> newMap =
                    TreeMultimap.create(String.CASE_INSENSITIVE_ORDER, Ordering.allEqual());
            newMap.putAll(classNameLocations);
            newMap.putAll(newClassNameLocations);
            classNameLocations = ImmutableMultimap.copyOf(newMap);
        }
    }

    private ImmutableList<String> combineClassNamesWithLimit(Set<String> fullMatchingClassNames,
            Set<String> matchingClassNames, int limit) {
        if (fullMatchingClassNames.size() < limit) {
            int space = limit - fullMatchingClassNames.size();
            int numToAdd = Math.min(space, matchingClassNames.size());
            fullMatchingClassNames
                    .addAll(ImmutableList.copyOf(Iterables.limit(matchingClassNames, numToAdd)));
        }
        return ImmutableList.copyOf(fullMatchingClassNames);
    }

    @GuardedBy("this")
    private void updateCacheWithClasspathClasses(Multimap<String, Location> newClassNameLocations) {
        String javaClassPath = StandardSystemProperty.JAVA_CLASS_PATH.value();
        if (javaClassPath == null) {
            return;
        }
        for (String path : Splitter.on(File.pathSeparatorChar).split(javaClassPath)) {
            File file = new File(path);
            Location location = getLocationFromFile(file);
            if (location != null && !classpathLocations.contains(location)) {
                loadClassNames(location, newClassNameLocations);
                classpathLocations.add(location);
            }
        }
    }

    // TODO refactor this and above method which are nearly identical
    @GuardedBy("this")
    private void updateCacheWithBootstrapClasses(Multimap<String, Location> newClassNameLocations) {
        String bootClassPath = System.getProperty("sun.boot.class.path");
        if (bootClassPath == null) {
            return;
        }
        for (String path : Splitter.on(File.pathSeparatorChar).split(bootClassPath)) {
            File file = new File(path);
            Location location = getLocationFromFile(file);
            if (location != null && !classpathLocations.contains(location)) {
                loadClassNames(location, newClassNameLocations);
                classpathLocations.add(location);
            }
        }
    }

    private List<UiAnalyzedMethod> getAnalyzedMethods(Location location, String className)
            throws IOException {
        byte[] bytes = getBytes(location, className);
        return getAnalyzedMethods(bytes);
    }

    private List<UiAnalyzedMethod> getAnalyzedMethods(byte[] bytes) throws IOException {
        AnalyzingClassVisitor cv = new AnalyzingClassVisitor();
        ClassReader cr = new ClassReader(bytes);
        cr.accept(cv, 0);
        return cv.getAnalyzedMethods();
    }

    private List<UiAnalyzedMethod> getAnalyzedMethods(Class<?> clazz) {
        List<UiAnalyzedMethod> analyzedMethods = Lists.newArrayList();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isSynthetic() || Modifier.isNative(method.getModifiers())) {
                // don't add synthetic or native methods to the analyzed model
                continue;
            }
            ImmutableUiAnalyzedMethod.Builder builder = ImmutableUiAnalyzedMethod.builder();
            builder.name(method.getName());
            for (Class<?> parameterType : method.getParameterTypes()) {
                // Class.getName() for arrays returns internal notation (e.g. "[B" for byte array)
                // so using Type.getType().getClassName() instead
                builder.addParameterTypes(Type.getType(parameterType).getClassName());
            }
            // Class.getName() for arrays returns internal notation (e.g. "[B" for byte array)
            // so using Type.getType().getClassName() instead
            builder.returnType(Type.getType(method.getReturnType()).getClassName());
            builder.modifiers(method.getModifiers());
            for (Class<?> exceptionType : method.getExceptionTypes()) {
                builder.addExceptions(exceptionType.getName());
            }
            analyzedMethods.add(builder.build());
        }
        return analyzedMethods;
    }

    @GuardedBy("this")
    private void updateCache(ClassLoader loader, Multimap<String, Location> newClassNameLocations) {
        List<URL> urls = getURLs(loader);
        List<Location> locations = Lists.newArrayList();
        for (URL url : urls) {
            Location location = tryToGetFileFromURL(url, loader);
            if (location != null) {
                locations.add(location);
            }
        }
        for (Location location : locations) {
            if (!classpathLocations.contains(location)) {
                loadClassNames(location, newClassNameLocations);
                classpathLocations.add(location);
            }
        }
    }

    private @Nullable Location tryToGetFileFromURL(URL url, ClassLoader loader) {
        if (url.getProtocol().equals("vfs")) {
            // special case for jboss/wildfly
            try {
                return getFileFromJBossVfsURL(url, loader);
            } catch (Exception e) {
                logger.warn(e.getMessage(), e);
            }
            return null;
        }
        try {
            URI uri = url.toURI();
            if (uri.getScheme().equals("file")) {
                return getLocationFromFile(new File(uri));
            } else if (uri.getScheme().equals("jar")) {
                String f = uri.getSchemeSpecificPart();
                if (f.startsWith("file:")) {
                    return getLocationFromJarFile(f);
                }
            }
        } catch (URISyntaxException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
        }
        return null;
    }

    private List<URL> getURLs(ClassLoader loader) {
        if (loader instanceof URLClassLoader) {
            try {
                return Lists.newArrayList(((URLClassLoader) loader).getURLs());
            } catch (Exception e) {
                // tomcat WebappClassLoader.getURLs() throws NullPointerException after stop() has
                // been called on the WebappClassLoader (this happens, for example, after a webapp
                // fails to load)
                //
                // log exception at debug level
                logger.debug(e.getMessage(), e);
                return ImmutableList.of();
            }
        }
        // special case for jboss/wildfly
        try {
            return Collections.list(loader.getResources("/"));
        } catch (IOException e) {
            logger.warn(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    private List<ClassLoader> getKnownClassLoaders() {
        List<ClassLoader> loaders = analyzedWorld.getClassLoaders();
        if (loaders.isEmpty()) {
            // this is needed for testing the UI outside of javaagent
            ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            if (systemClassLoader == null) {
                return ImmutableList.of();
            } else {
                return ImmutableList.of(systemClassLoader);
            }
        }
        return loaders;
    }

    private static void loadClassNames(Location location,
            Multimap<String, Location> newClassNameLocations) {
        try {
            File dir = location.directory();
            File jarFile = location.jarFile();
            if (dir != null) {
                loadClassNamesFromDirectory(dir, "", location, newClassNameLocations);
            } else if (jarFile != null) {
                String nestedJarFilePath = location.nestedJarFilePath();
                if (nestedJarFilePath == null) {
                    loadClassNamesFromJarFile(jarFile, location, newClassNameLocations);
                } else {
                    loadClassNamesFromNestedJarFile(jarFile, nestedJarFilePath, location,
                            newClassNameLocations);
                }
            } else {
                throw new AssertionError("Both Location directory() and jarFile() are null");
            }
        } catch (IllegalArgumentException e) {
            // File(URI) constructor can throw IllegalArgumentException
            logger.debug(e.getMessage(), e);
        } catch (IOException e) {
            logger.debug("error reading classes from file: {}", location, e);
        }
    }

    private static void loadClassNamesFromDirectory(File dir, String prefix, Location location,
            Multimap<String, Location> newClassNameLocations) throws MalformedURLException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.endsWith(".class")) {
                String className = prefix + name.substring(0, name.lastIndexOf('.'));
                newClassNameLocations.put(className, location);
            } else if (file.isDirectory()) {
                loadClassNamesFromDirectory(file, prefix + name + ".", location,
                        newClassNameLocations);
            }
        }
    }

    private static void loadClassNamesFromJarFile(File jarFile, Location location,
            Multimap<String, Location> newClassNameLocations) throws IOException {
        Closer closer = Closer.create();
        InputStream s = new FileInputStream(jarFile);
        JarInputStream jarIn = closer.register(new JarInputStream(s));
        try {
            loadClassNamesFromManifestClassPath(jarIn, jarFile, newClassNameLocations);
            loadClassNamesFromJarInputStream(jarIn, location, newClassNameLocations);
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    private static void loadClassNamesFromNestedJarFile(File jarFile, String nestedJarFilePath,
            Location location, Multimap<String, Location> newClassNameLocations)
            throws IOException {
        URI uri;
        try {
            uri = new URI("jar", "file:" + jarFile.getPath() + "!/" + nestedJarFilePath, "");
        } catch (URISyntaxException e) {
            // this is a programmatic error
            throw new RuntimeException(e);
        }
        Closer closer = Closer.create();
        InputStream s = uri.toURL().openStream();
        JarInputStream jarIn = closer.register(new JarInputStream(s));
        try {
            loadClassNamesFromJarInputStream(jarIn, location, newClassNameLocations);
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    private static void loadClassNamesFromManifestClassPath(JarInputStream jarIn, File jarFile,
            Multimap<String, Location> newClassNameLocations) {
        Manifest manifest = jarIn.getManifest();
        if (manifest == null) {
            return;
        }
        String classpath = manifest.getMainAttributes().getValue("Class-Path");
        if (classpath == null) {
            return;
        }
        URI baseUri = jarFile.toURI();
        for (String path : Splitter.on(' ').omitEmptyStrings().split(classpath)) {
            File file = new File(baseUri.resolve(path));
            Location location = getLocationFromFile(file);
            if (location != null) {
                loadClassNames(location, newClassNameLocations);
            }
        }
    }

    private static void loadClassNamesFromJarInputStream(JarInputStream jarIn, Location location,
            Multimap<String, Location> newClassNameLocations) throws IOException {
        JarEntry jarEntry;
        while ((jarEntry = jarIn.getNextJarEntry()) != null) {
            if (jarEntry.isDirectory()) {
                continue;
            }
            String name = jarEntry.getName();
            if (!name.endsWith(".class")) {
                continue;
            }
            String className = name.substring(0, name.lastIndexOf('.')).replace('/', '.');
            newClassNameLocations.put(className, location);
        }
    }

    private static @Nullable Location getFileFromJBossVfsURL(URL url, ClassLoader loader)
            throws Exception {
        Object virtualFile = url.openConnection().getContent();
        Class<?> virtualFileClass = loader.loadClass("org.jboss.vfs.VirtualFile");
        Method getPhysicalFileMethod = Reflections.getMethod(virtualFileClass, "getPhysicalFile");
        Method getNameMethod = Reflections.getMethod(virtualFileClass, "getName");
        File physicalFile = (File) Reflections.invoke(getPhysicalFileMethod, virtualFile);
        checkNotNull(physicalFile, "org.jboss.vfs.VirtualFile.getPhysicalFile() returned null");
        String name = (String) Reflections.invoke(getNameMethod, virtualFile);
        checkNotNull(name, "org.jboss.vfs.VirtualFile.getName() returned null");
        File file = new File(physicalFile.getParentFile(), name);
        return getLocationFromFile(file);
    }

    @Value.Immutable(prehash = true)
    interface UiAnalyzedMethod {
        String name();
        // these are class names
        ImmutableList<String> parameterTypes();
        String returnType();
        int modifiers();
        @Nullable
        String signature();
        ImmutableList<String> exceptions();
    }

    private static class PartialClassNameMatcher {

        private final String partialClassNameUpper;
        private final String prefixedPartialClassNameUpper1;
        private final String prefixedPartialClassNameUpper2;

        private PartialClassNameMatcher(String partialClassName) {
            partialClassNameUpper = partialClassName.toUpperCase(Locale.ENGLISH);
            prefixedPartialClassNameUpper1 = '.' + partialClassNameUpper;
            prefixedPartialClassNameUpper2 = '$' + partialClassNameUpper;
        }

        private boolean isPotentialFullMatch(String classNameUpper) {
            return classNameUpper.equals(partialClassNameUpper)
                    || classNameUpper.endsWith(prefixedPartialClassNameUpper1)
                    || classNameUpper.endsWith(prefixedPartialClassNameUpper2);
        }

        private boolean isPotentialMatch(String classNameUpper) {
            return classNameUpper.startsWith(partialClassNameUpper)
                    || classNameUpper.contains(prefixedPartialClassNameUpper1)
                    || classNameUpper.contains(prefixedPartialClassNameUpper2);
        }
    }

    private static class AnalyzingClassVisitor extends ClassVisitor {

        private final List<UiAnalyzedMethod> analyzedMethods = Lists.newArrayList();

        private AnalyzingClassVisitor() {
            super(ASM5);
        }

        @Override
        public @Nullable MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/[] exceptions) {
            if ((access & ACC_SYNTHETIC) != 0 || (access & ACC_NATIVE) != 0) {
                // don't add synthetic or native methods to the analyzed model
                return null;
            }
            if (name.equals("<init>")) {
                // don't add constructors to the analyzed model
                return null;
            }
            ImmutableUiAnalyzedMethod.Builder builder = ImmutableUiAnalyzedMethod.builder();
            builder.name(name);
            for (Type parameterType : Type.getArgumentTypes(desc)) {
                builder.addParameterTypes(parameterType.getClassName());
            }
            builder.returnType(Type.getReturnType(desc).getClassName());
            builder.modifiers(access);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    builder.addExceptions(ClassNames.fromInternalName(exception));
                }
            }
            analyzedMethods.add(builder.build());
            return null;
        }

        private List<UiAnalyzedMethod> getAnalyzedMethods() {
            return analyzedMethods;
        }
    }

    private static @Nullable Location getLocationFromFile(File file) {
        if (!file.exists()) {
            return null;
        } else if (file.isDirectory()) {
            return ImmutableLocation.builder().directory(file).build();
        } else if (file.getName().endsWith(".jar")) {
            return ImmutableLocation.builder().jarFile(file).build();
        } else {
            return null;
        }
    }

    private static Location getLocationFromJarFile(String f) {
        int index = f.indexOf("!/");
        File jarFile = new File(f.substring(5, index));
        String nestedJarFilePath = f.substring(index + 2);
        if (nestedJarFilePath.isEmpty()) {
            // the jar file itself
            return ImmutableLocation.builder().jarFile(jarFile).build();
        }
        // strip off trailing !/
        nestedJarFilePath =
                nestedJarFilePath.substring(0, nestedJarFilePath.length() - 2);
        return ImmutableLocation.builder()
                .jarFile(jarFile)
                .nestedJarFilePath(nestedJarFilePath)
                .build();
    }

    private static byte[] getBytes(Location location, String className) throws IOException {
        String name = className.replace('.', '/') + ".class";
        File dir = location.directory();
        File jarFile = location.jarFile();
        if (dir != null) {
            URI uri = new File(dir, name).toURI();
            return Resources.toByteArray(uri.toURL());
        } else if (jarFile != null) {
            String nestedJarFilePath = location.nestedJarFilePath();
            if (nestedJarFilePath == null) {
                return getBytesFromJarFile(name, jarFile);
            } else {
                return getBytesFromNestedJarFile(name, jarFile, nestedJarFilePath);
            }
        } else {
            throw new AssertionError("Both Location directory() and jarFile() are null");
        }
    }

    private static byte[] getBytesFromJarFile(String name, File jarFile) throws IOException {
        String path = jarFile.getPath();
        URI uri;
        try {
            uri = new URI("jar", "file:" + path + "!/" + name, "");
        } catch (URISyntaxException e) {
            // this is a programmatic error
            throw new RuntimeException(e);
        }
        return Resources.toByteArray(uri.toURL());
    }

    private static byte[] getBytesFromNestedJarFile(String name, File jarFile,
            String nestedJarFilePath) throws IOException {
        String path = jarFile.getPath();
        URI uri;
        try {
            uri = new URI("jar", "file:" + path + "!/" + nestedJarFilePath, "");
        } catch (URISyntaxException e) {
            // this is a programmatic error
            throw new RuntimeException(e);
        }
        Closer closer = Closer.create();
        InputStream s = uri.toURL().openStream();
        JarInputStream jarIn = closer.register(new JarInputStream(s));
        try {
            JarEntry jarEntry;
            while ((jarEntry = jarIn.getNextJarEntry()) != null) {
                if (jarEntry.isDirectory()) {
                    continue;
                }
                if (jarEntry.getName().equals(name)) {
                    return ByteStreams.toByteArray(jarIn);
                }
            }
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
        throw new UnsupportedOperationException();
    }

    @Value.Immutable
    interface Location {
        @Nullable
        File directory();
        @Nullable
        File jarFile();
        @Nullable
        String nestedJarFilePath();
    }
}
