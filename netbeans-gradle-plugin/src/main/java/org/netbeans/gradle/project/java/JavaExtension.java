package org.netbeans.gradle.project.java;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.classpath.GlobalPathRegistry;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.gradle.project.ProjectInitListener;
import org.netbeans.gradle.project.api.entry.GradleProjectExtension2;
import org.netbeans.gradle.project.java.model.JavaSourceDirHandler;
import org.netbeans.gradle.project.java.model.NbJavaModel;
import org.netbeans.gradle.project.java.model.idea.IdeaJavaModelUtils;
import org.netbeans.gradle.project.java.query.GradleAnnotationProcessingQuery;
import org.netbeans.gradle.project.java.query.GradleBinaryForSourceQuery;
import org.netbeans.gradle.project.java.query.GradleClassPathProvider;
import org.netbeans.gradle.project.java.query.GradleProjectSources;
import org.netbeans.gradle.project.java.query.GradleProjectTemplates;
import org.netbeans.gradle.project.java.query.GradleSourceForBinaryQuery;
import org.netbeans.gradle.project.java.query.GradleSourceLevelQueryImplementation;
import org.netbeans.gradle.project.java.query.GradleUnitTestFinder;
import org.netbeans.gradle.project.java.query.J2SEPlatformFromScriptQueryImpl;
import org.netbeans.gradle.project.java.query.JavaExtensionNodes;
import org.netbeans.gradle.project.java.query.JavaInitScriptQuery;
import org.netbeans.gradle.project.java.query.JavaProjectContextActions;
import org.netbeans.gradle.project.java.tasks.GradleJavaBuiltInCommands;
import org.netbeans.spi.project.ui.ProjectOpenedHook;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;
import org.openide.util.lookup.ProxyLookup;

public final class JavaExtension implements GradleProjectExtension2<NbJavaModel> {
    private final Project project;
    private final File projectDirectoryAsFile;
    private volatile NbJavaModel currentModel;
    private volatile boolean hasEverBeenLoaded;

    private final GradleClassPathProvider cpProvider;
    private final AtomicReference<JavaSourceDirHandler> sourceDirsHandlerRef;

    private final AtomicReference<Lookup> projectLookupRef;
    private final AtomicReference<Lookup> permanentLookupRef;
    private final AtomicReference<Lookup> extensionLookupRef;
    private final AtomicReference<Lookup> combinedLookupRef;

    private JavaExtension(Project project) throws IOException {
        if (project == null) throw new NullPointerException("project");

        this.projectDirectoryAsFile = FileUtil.toFile(project.getProjectDirectory());
        if (projectDirectoryAsFile == null) throw new NullPointerException("projectDirAsFile");

        this.project = project;
        this.currentModel = IdeaJavaModelUtils.createEmptyModel(project.getProjectDirectory());
        this.cpProvider = new GradleClassPathProvider(this);
        this.projectLookupRef = new AtomicReference<Lookup>(null);
        this.permanentLookupRef = new AtomicReference<Lookup>(null);
        this.extensionLookupRef = new AtomicReference<Lookup>(null);
        this.combinedLookupRef = new AtomicReference<Lookup>(null);
        this.hasEverBeenLoaded = false;
        this.sourceDirsHandlerRef = new AtomicReference<JavaSourceDirHandler>(null);
    }

    public JavaSourceDirHandler getSourceDirsHandler() {
        JavaSourceDirHandler result = sourceDirsHandlerRef.get();
        if (result == null) {
            sourceDirsHandlerRef.compareAndSet(null, new JavaSourceDirHandler(this));
            result = sourceDirsHandlerRef.get();
        }
        return result;
    }

    public static JavaExtension create(Project project) throws IOException {
        return new JavaExtension(project);
    }

    public boolean isOwnerProject(File file) {
        FileObject fileObj;

        File currentFile = file;
        fileObj = FileUtil.toFileObject(currentFile);
        while (fileObj == null) {
            currentFile = currentFile.getParentFile();
            if (currentFile == null) {
                return false;
            }

            fileObj = FileUtil.toFileObject(currentFile);
        }

        return isOwnerProject(fileObj);
    }

    public boolean isOwnerProject(FileObject file) {
        Project owner = FileOwnerQuery.getOwner(file);
        if (owner == null) {
            return false;
        }

        return project.getProjectDirectory().equals(owner.getProjectDirectory());
    }

    public NbJavaModel getCurrentModel() {
        return currentModel;
    }

    private void initLookup(Lookup lookup) {
        for (ProjectInitListener listener: lookup.lookupAll(ProjectInitListener.class)) {
            listener.onInitProject();
        }
    }

    // These classes are on the lookup always.
    @Override
    public Lookup getPermanentProjectLookup() {
        Lookup lookup = permanentLookupRef.get();
        if (lookup == null) {
            lookup = Lookups.fixed(this, new OpenHook());

            if (permanentLookupRef.compareAndSet(null, lookup)) {
                initLookup(lookup);
            }
            lookup = permanentLookupRef.get();
        }
        return lookup;
    }

    @Override
    public Lookup getProjectLookup() {
        Lookup lookup = projectLookupRef.get();
        if (lookup == null) {
            lookup = Lookups.fixed(
                    new GradleProjectSources(this),
                    cpProvider,
                    new GradleSourceLevelQueryImplementation(this),
                    new GradleUnitTestFinder(this),
                    new GradleAnnotationProcessingQuery(),
                    new GradleSourceForBinaryQuery(this),
                    new GradleBinaryForSourceQuery(this),
                    new GradleProjectTemplates(),
                    new J2SEPlatformFromScriptQueryImpl(this) // internal use only
                    );

            if (projectLookupRef.compareAndSet(null, lookup)) {
                initLookup(lookup);
            }
            lookup = projectLookupRef.get();
        }
        return lookup;
    }

    @Override
    public Lookup getExtensionLookup() {
        Lookup lookup = extensionLookupRef.get();
        if (lookup == null) {
            lookup = Lookups.fixed(new JavaExtensionNodes(this),
                    new JavaProjectContextActions(this),
                    new GradleJavaBuiltInCommands(this),
                    new JavaInitScriptQuery());

            if (extensionLookupRef.compareAndSet(null, lookup)) {
                initLookup(lookup);
            }
            lookup = extensionLookupRef.get();
        }
        return lookup;
    }

    public Project getProject() {
        return project;
    }

    public Lookup getOwnerProjectLookup() {
        return project.getLookup();
    }

    public FileObject getProjectDirectory() {
        return project.getProjectDirectory();
    }

    public File getProjectDirectoryAsFile() {
        return projectDirectoryAsFile;
    }

    public String getName() {
        return currentModel.getMainModule().getShortName();
    }

    public boolean hasEverBeenLoaded() {
        return hasEverBeenLoaded;
    }

    private Lookup getCombinedLookup() {
        Lookup lookup = combinedLookupRef.get();
        if (lookup == null) {
            lookup = new ProxyLookup(
                    getPermanentProjectLookup(),
                    getProjectLookup(),
                    getExtensionLookup());
            combinedLookupRef.compareAndSet(null, lookup);
            lookup = combinedLookupRef.get();
        }
        return lookup;
    }

    private void fireModelChange() {
        for (JavaModelChangeListener listener: getCombinedLookup().lookupAll(JavaModelChangeListener.class)) {
            listener.onModelChange();
        }
    }

    @Override
    public void activateExtension(NbJavaModel parsedModel) {
        if (parsedModel == null) throw new NullPointerException("parsedModel");

        currentModel = parsedModel;
        hasEverBeenLoaded = true;

        fireModelChange();
    }

    @Override
    public void deactivateExtension() {
    }

    // OpenHook is important for debugging because the debugger relies on the
    // globally registered source class paths for source stepping.

    // SwingUtilities.invokeLater is used only to guarantee the order of events.
    // Actually any executor which executes tasks in the order they were
    // submitted to it is good (using SwingUtilities.invokeLater was only
    // convenient to use because registering paths is cheap enough).
    private class OpenHook extends ProjectOpenedHook implements PropertyChangeListener {
        private final List<GlobalPathReg> paths;
        private boolean opened;

        public OpenHook() {
            this.opened = false;

            this.paths = new LinkedList<GlobalPathReg>();
            this.paths.add(new GlobalPathReg(ClassPath.SOURCE));
            this.paths.add(new GlobalPathReg(ClassPath.BOOT));
            this.paths.add(new GlobalPathReg(ClassPath.COMPILE));
            this.paths.add(new GlobalPathReg(ClassPath.EXECUTE));
        }

        @Override
        protected void projectOpened() {
            cpProvider.addPropertyChangeListener(this);

            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    opened = true;
                    doRegisterClassPaths();
                }
            });
        }

        @Override
        protected void projectClosed() {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    opened = false;
                    doUnregisterPaths();
                }
            });

            cpProvider.removePropertyChangeListener(this);
        }

        private void doUnregisterPaths() {
            assert SwingUtilities.isEventDispatchThread();

            for (GlobalPathReg pathReg: paths) {
                pathReg.unregister();
            }
        }

        private void doRegisterClassPaths() {
            assert SwingUtilities.isEventDispatchThread();

            for (GlobalPathReg pathReg: paths) {
                pathReg.register();
            }
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    if (opened) {
                        doRegisterClassPaths();
                    }
                }
            });
        }
    }

    private class GlobalPathReg {
        private final String type;
        // Note that using AtomicReference does not really make the methods
        // thread-safe but is only convenient to use.
        private final AtomicReference<ClassPath[]> paths;

        public GlobalPathReg(String type) {
            this.type = type;
            this.paths = new AtomicReference<ClassPath[]>(null);
        }

        private void replaceRegistration(ClassPath[] newPaths) {
            GlobalPathRegistry registry = GlobalPathRegistry.getDefault();

            ClassPath[] oldPaths = paths.getAndSet(newPaths);
            if (oldPaths != null) {
                registry.unregister(type, oldPaths);
            }
            if (newPaths != null) {
                registry.register(type, newPaths);
            }
        }

        public void register() {
            ClassPath[] newPaths = new ClassPath[]{cpProvider.getClassPaths(type)};
            replaceRegistration(newPaths);
        }

        public void unregister() {
            replaceRegistration(null);
        }
    }
}
