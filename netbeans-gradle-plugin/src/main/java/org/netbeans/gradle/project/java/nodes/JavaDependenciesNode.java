package org.netbeans.gradle.project.java.nodes;

import java.awt.Image;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.project.Project;
import org.netbeans.gradle.model.java.JavaClassPaths;
import org.netbeans.gradle.model.java.JavaSourceSet;
import org.netbeans.gradle.project.NbIcons;
import org.netbeans.gradle.project.NbStrings;
import org.netbeans.gradle.project.api.nodes.SingleNodeFactory;
import org.netbeans.gradle.project.java.JavaExtension;
import org.netbeans.gradle.project.java.JavaModelChangeListener;
import org.netbeans.gradle.project.java.model.JavaProjectDependency;
import org.netbeans.gradle.project.java.model.NbJavaModel;
import org.netbeans.gradle.project.java.model.NbJavaModule;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectNotFoundException;
import org.openide.nodes.AbstractNode;
import org.openide.nodes.ChildFactory;
import org.openide.nodes.Children;
import org.openide.nodes.FilterNode;
import org.openide.nodes.Node;

public final class JavaDependenciesNode extends AbstractNode implements JavaModelChangeListener {
    private static final Logger LOGGER = Logger.getLogger(JavaDependenciesNode.class.getName());

    private final DependenciesChildFactory childFactory;

    public JavaDependenciesNode(JavaExtension javaExt) {
        this(new DependenciesChildFactory(javaExt));
    }

    private JavaDependenciesNode(DependenciesChildFactory childFactory) {
        super(createChildren(childFactory));

        this.childFactory = childFactory;
    }

    private static Children createChildren(DependenciesChildFactory childFactory) {
        return Children.create(childFactory, true);
    }

    @Override
    public void onModelChange() {
        childFactory.stateChanged();
    }

    @Override
    public Image getIcon(int type) {
        return NbIcons.getLibrariesIcon();
    }

    @Override
    public Image getOpenedIcon(int type) {
        return getIcon(type);
    }

    @Override
    public String getDisplayName() {
        return NbStrings.getDependenciesNodeCaption();
    }

    private static class DependenciesChildFactory
    extends
            ChildFactory.Detachable<SingleNodeFactory> {

        private final JavaExtension javaExt;

        public DependenciesChildFactory(JavaExtension javaExt) {
            if (javaExt == null) throw new NullPointerException("javaExt");
            this.javaExt = javaExt;
        }

        public void stateChanged() {
            refresh(false);
        }

        private void addDependencyGroup(
                final String groupName,
                final Collection<SingleNodeFactory> dependencies,
                List<SingleNodeFactory> toPopulate) {

            if (dependencies.isEmpty()) {
                return;
            }

            toPopulate.add(new SingleNodeFactory() {
                @Override
                public Node createNode() {
                    return new AbstractNode(Children.create(new DependencyGroupChildFactory(dependencies), true)) {
                        @Override
                        public Image getIcon(int type) {
                            return NbIcons.getLibrariesIcon();
                        }

                        @Override
                        public Image getOpenedIcon(int type) {
                            return getIcon(type);
                        }

                        @Override
                        public String getDisplayName() {
                            return groupName;
                        }
                    };
                }
            });
        }

        private static List<SingleNodeFactory> filesToNodes(NbJavaModel currentModel, Collection<File> files) {
            List<SingleNodeFactory> result = new ArrayList<SingleNodeFactory>(files.size());
            for (File file: files) {
                JavaProjectDependency projectDep = currentModel.tryGetDepedency(file);
                if (projectDep == null) {
                    result.add(new FileDependency(file));
                }
                else {
                    result.add(new ProjectDependencyFactory(projectDep));
                }
            }
            return result;
        }

        private static List<String> removeInherited(
                Set<File> compileClasspaths,
                NbJavaModule module) {

            List<String> result = new ArrayList<String>();
            for (JavaSourceSet sourceSet: module.getSources()) {
                if (compileClasspaths.remove(sourceSet.getOutputDirs().getClassesDir())) {
                    result.add(sourceSet.getName());
                }
            }
            return result;
        }

        private static String listToString(Collection<?> list) {
            StringBuilder result = new StringBuilder();
            boolean first = true;
            for (Object element: list) {
                if (first) {
                    first = false;
                }
                else {
                    result.append(", ");
                }
                result.append(element != null ? element.toString() : "null");
            }
            return result.toString();
        }

        private static String getBaseDependencyGroupName(
                DependencyType dependencyType,
                JavaSourceSet sourceSet) {

            switch (dependencyType) {
                case COMPILE:
                    return "Compile for " + sourceSet.getName();
                case RUNTIME:
                    return "Runtime for " + sourceSet.getName();
                case PROVIDED:
                    return "Provided for " + sourceSet.getName();
                default:
                    throw new AssertionError(dependencyType.name());
            }
        }

        private String getNameForDependencyGroup(
                DependencyType dependencyType,
                JavaSourceSet sourceSet,
                Map<String, Set<String>> sourceSetDependencyGraph) {

            String baseName = getBaseDependencyGroupName(dependencyType, sourceSet);
            Set<String> dependencies = sourceSetDependencyGraph.get(sourceSet.getName());
            if (dependencies == null || dependencies.isEmpty()) {
                return baseName;
            }
            else {
                return baseName + " (inherits " + listToString(dependencies) + ")";
            }
        }

        private void addSourceSetDependencyNodes(
                NbJavaModel currentModel,
                String nodeGroupName,
                Collection<String> sourceSetDependencies,
                Set<File> classpaths, // IN/OUT
                List<SingleNodeFactory> toPopulate) {

            NbJavaModule mainModule = currentModel.getMainModule();
            classpaths.removeAll(mainModule.getAllBuildOutputs());

            for (String inheritedName: sourceSetDependencies) {
                JavaSourceSet inherited = mainModule.tryGetSourceSetByName(inheritedName);
                if (inherited != null) {
                    classpaths.removeAll(inherited.getClasspaths().getCompileClasspaths());
                    classpaths.removeAll(inherited.getClasspaths().getRuntimeClasspaths());
                }
            }

            List<SingleNodeFactory> dependencyNodes = filesToNodes(currentModel, classpaths);
            addDependencyGroup(nodeGroupName, dependencyNodes, toPopulate);
        }

        private static <T> Set<T> splitSets(Set<T> set1, Set<T> set2) {
            Set<T> smallerSet;
            Set<T> largerSet;

            if (set1.size() < set2.size()) {
                smallerSet = set1;
                largerSet = set2;
            }
            else {
                smallerSet = set2;
                largerSet = set1;
            }

            Set<T> intersect = new HashSet<T>();
            for (T element: smallerSet) {
                if (largerSet.remove(element)) {
                    intersect.add(element);
                }
            }
            smallerSet.removeAll(intersect);

            return intersect;
        }

        private static Map<String, Set<String>> sourceSetDependencyGraph(
                NbJavaModule mainModule) {

            Map<File, String> buildOutput = new HashMap<File, String>();
            for (JavaSourceSet sourceSet: mainModule.getSources()) {
                buildOutput.put(sourceSet.getOutputDirs().getClassesDir(), sourceSet.getName());
            }

            Map<String, Set<String>> result = new HashMap<String, Set<String>>();
            for (JavaSourceSet sourceSet: mainModule.getSources()) {
                Set<File> runtimeClasspaths = sourceSet.getClasspaths().getRuntimeClasspaths();

                Set<String> dependencies = new HashSet<String>();
                for (Map.Entry<File, String> entry: buildOutput.entrySet()) {
                    if (runtimeClasspaths.contains(entry.getKey())) {
                        dependencies.add(entry.getValue());
                    }
                }

                result.put(sourceSet.getName(), dependencies);
            }

            // TODO: Remove redundant edges

            return result;
        }

        private void readKeys(List<SingleNodeFactory> toPopulate) throws DataObjectNotFoundException {
            NbJavaModel currentModel = javaExt.getCurrentModel();
            NbJavaModule mainModule = currentModel.getMainModule();

            Map<String, Set<String>> dependencyGraph = sourceSetDependencyGraph(mainModule);

            for (JavaSourceSet sourceSet: mainModule.getSources()) {
                JavaClassPaths classpaths = sourceSet.getClasspaths();
                // TODO: 1. Adjust node names (localize)
                //       2. Order dependencies: Projects first

                Set<File> providedClassPaths = new HashSet<File>(classpaths.getCompileClasspaths());
                Set<File> runtimeClassPaths = new HashSet<File>(classpaths.getRuntimeClasspaths());
                Set<File> compileClassPaths = splitSets(providedClassPaths, runtimeClassPaths);

                Set<String> sourceDependencies = dependencyGraph.get(sourceSet.getName());
                if (sourceDependencies == null) {
                    sourceDependencies = Collections.emptySet();
                }

                addSourceSetDependencyNodes(
                        currentModel,
                        getNameForDependencyGroup(DependencyType.COMPILE, sourceSet, dependencyGraph),
                        sourceDependencies,
                        compileClassPaths,
                        toPopulate);

                addSourceSetDependencyNodes(
                        currentModel,
                        getNameForDependencyGroup(DependencyType.PROVIDED, sourceSet, dependencyGraph),
                        sourceDependencies,
                        providedClassPaths,
                        toPopulate);

                addSourceSetDependencyNodes(
                        currentModel,
                        getNameForDependencyGroup(DependencyType.RUNTIME, sourceSet, dependencyGraph),
                        sourceDependencies,
                        runtimeClassPaths,
                        toPopulate);
            }

            LOGGER.fine("Dependencies for the Gradle project were found.");
        }

        @Override
        protected boolean createKeys(List<SingleNodeFactory> toPopulate) {
            try {
                readKeys(toPopulate);
            } catch (DataObjectNotFoundException ex) {
                throw new RuntimeException(ex);
            }
            return true;
        }

        @Override
        protected Node createNodeForKey(SingleNodeFactory key) {
            return key.createNode();
        }
    }

    private static class DependencyGroupChildFactory extends ChildFactory<SingleNodeFactory> {
        private final List<SingleNodeFactory> dependencies;

        public DependencyGroupChildFactory(Collection<SingleNodeFactory> dependencies) {
            this.dependencies = new ArrayList<SingleNodeFactory>(dependencies);
        }

        protected void readKeys(List<SingleNodeFactory> toPopulate) throws DataObjectNotFoundException {
            toPopulate.addAll(dependencies);
        }

        @Override
        protected boolean createKeys(List<SingleNodeFactory> toPopulate) {
            try {
                readKeys(toPopulate);
            } catch (DataObjectNotFoundException ex) {
                throw new RuntimeException(ex);
            }
            return true;
        }

        @Override
        protected Node createNodeForKey(SingleNodeFactory key) {
            return key.createNode();
        }
    }

    private static final class FileDependency implements SingleNodeFactory {
        private final File file;

        public FileDependency(File file) {
            assert file != null;

            this.file = file;
        }

        @Override
        public Node createNode() {
            File normalizedFile = FileUtil.normalizeFile(file);
            FileObject fileObj = normalizedFile != null ? FileUtil.toFileObject(normalizedFile) : null;
            if (fileObj == null) {
                LOGGER.log(Level.WARNING, "Dependency is not available: {0}", file);
                return null;
            }

            final DataObject dataObj;
            try {
                dataObj = DataObject.find(fileObj);
            } catch (DataObjectNotFoundException ex) {
                LOGGER.log(Level.INFO, "Unexpected DataObjectNotFoundException for file: " + file, ex);
                return null;
            }
            return dataObj.getNodeDelegate().cloneNode();
        }
    }

    private static final class ProjectDependencyFactory implements SingleNodeFactory {
        private final JavaProjectDependency projectDep;

        public ProjectDependencyFactory(JavaProjectDependency projectDep) {
            this.projectDep = projectDep;
        }

        private DataObject getProjectDirObj() {
            Project project = projectDep.getProjectReference().tryGetProject();
            if (project == null) {
                return null;
            }
            try {
                return DataObject.find(project.getProjectDirectory());
            } catch (DataObjectNotFoundException ex) {
                LOGGER.log(Level.INFO, "Failed to find node for project directory: " + project.getProjectDirectory(), ex);
                return null;
            }
        }

        @Override
        public Node createNode() {
            DataObject fileObject = getProjectDirObj();

            Node baseNode = fileObject != null
                    ? fileObject.getNodeDelegate()
                    : Node.EMPTY;

            // TODO: Update the created node if the underlying project is reloaded.
            return new FilterNode(baseNode.cloneNode()) {
                @Override
                public Image getIcon(int type) {
                    return NbIcons.getGradleIcon();
                }

                @Override
                public Image getOpenedIcon(int type) {
                    return getIcon(type);
                }

                @Override
                public String getDisplayName() {
                    NbJavaModule module = projectDep.tryGetModule();
                    return module != null
                            ? module.getShortName()
                            : super.getDisplayName();
                }
            };
        }
    }

    private enum DependencyType {
        COMPILE,
        RUNTIME,
        PROVIDED
    }
}
