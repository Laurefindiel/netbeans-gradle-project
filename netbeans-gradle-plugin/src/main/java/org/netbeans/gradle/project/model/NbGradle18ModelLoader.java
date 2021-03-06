package org.netbeans.gradle.project.model;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gradle.tooling.ProjectConnection;
import org.netbeans.api.progress.ProgressHandle;
import org.netbeans.gradle.model.BuilderIssue;
import org.netbeans.gradle.model.BuilderResult;
import org.netbeans.gradle.model.FetchedModels;
import org.netbeans.gradle.model.FetchedProjectModels;
import org.netbeans.gradle.model.GenericModelFetcher;
import org.netbeans.gradle.model.GenericProjectProperties;
import org.netbeans.gradle.model.GradleBuildInfoQuery;
import org.netbeans.gradle.model.OperationInitializer;
import org.netbeans.gradle.model.api.GradleProjectInfoQuery;
import org.netbeans.gradle.model.util.CollectionUtils;
import org.netbeans.gradle.project.NbGradleExtensionRef;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.gradle.project.NbStrings;
import org.netbeans.gradle.project.api.entry.GradleProjectExtensionDef;
import org.netbeans.gradle.project.api.entry.ModelLoadResult;
import org.netbeans.gradle.project.api.entry.ParsedModel;
import org.netbeans.gradle.project.api.modelquery.GradleModelDef;
import org.netbeans.gradle.project.api.modelquery.GradleModelDefQuery2;
import org.netbeans.gradle.project.api.modelquery.GradleTarget;
import org.netbeans.gradle.project.model.issue.ModelLoadIssue;
import org.netbeans.gradle.project.model.issue.ModelLoadIssues;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

public final class NbGradle18ModelLoader implements NbModelLoader {
    private static final Logger LOGGER = Logger.getLogger(NbGradle18ModelLoader.class.getName());

    private final GradleTarget gradleTarget;
    private final OperationInitializer setup;

    public NbGradle18ModelLoader(OperationInitializer setup, GradleTarget gradleTarget) {
        if (setup == null) throw new NullPointerException("setup");
        if (gradleTarget == null) throw new NullPointerException("gradleTarget");

        this.gradleTarget = gradleTarget;
        this.setup = setup;
    }

    private static GradleModelDefQuery2 getBasicModelQuery(final GradleProjectExtensionDef<?> extension) {
        return new GradleModelDefQuery2() {
            @Override
            public GradleModelDef getModelDef(GradleTarget gradleTarget) {
                Collection<Class<?>> toolinModels = NbCompatibleModelLoader.getBasicModels(extension, gradleTarget);
                if (toolinModels.isEmpty()) {
                    return GradleModelDef.EMPTY;
                }

                return new GradleModelDef(
                        toolinModels,
                        Collections.<GradleProjectInfoQuery<?>>emptyList());
            }
        };
    }

    private static <E> void addAllNullSafe(Collection<? super E> collection, Collection<? extends E> toAdd) {
        if (toAdd != null) {
            collection.addAll(toAdd);
        }
    }

    private static GradleModelDef safelyReturn(GradleModelDef result, GradleProjectExtensionDef<?> extension) {
        if (result == null) {
            LOGGER.log(Level.WARNING,
                    "GradleModelDefQuery2.getModelDef returned null for extension {0}",
                    extension.getName());
            return GradleModelDef.EMPTY;
        }
        else {
            return result;
        }
    }

    private static GradleModelDefQuery2 getModelQuery(final GradleProjectExtensionDef<?> extension) {
        return new GradleModelDefQuery2() {
            @Override
            public GradleModelDef getModelDef(GradleTarget gradleTarget) {
                Lookup lookup = extension.getLookup();
                Collection<? extends GradleModelDefQuery2> queries = lookup.lookupAll(GradleModelDefQuery2.class);
                int queryCount = queries.size();
                if (queryCount == 0) {
                    return getBasicModelQuery(extension).getModelDef(gradleTarget);
                }

                if (queryCount == 1) {
                    return queries.iterator().next().getModelDef(gradleTarget);
                }

                List<GradleProjectInfoQuery<?>> projectInfoQueries = new LinkedList<GradleProjectInfoQuery<?>>();
                List<Class<?>> toolingModels = new LinkedList<Class<?>>();

                for (GradleModelDefQuery2 query: queries) {
                    GradleModelDef modelDef = safelyReturn(query.getModelDef(gradleTarget), extension);
                    projectInfoQueries.addAll(modelDef.getProjectInfoQueries());
                    toolingModels.addAll(modelDef.getToolingModels());
                }
                return new GradleModelDef(toolingModels, projectInfoQueries);
            }
        };
    }

    private static <K, V> void addAllToMultiMap(K key, Collection<? extends V> newValues, Map<? super K, List<V>> map) {
        if (newValues.isEmpty()) {
            return;
        }

        List<V> values = map.get(key);
        if (values == null) {
            values = new LinkedList<V>();
            map.put(key, values);
        }
        values.addAll(newValues);
    }

    @Override
    public Result loadModels(
            NbGradleProject project,
            ProjectConnection connection,
            ProgressHandle progress) throws IOException {

        ProjectModelFetcher modelFetcher = new ProjectModelFetcher(project, gradleTarget);
        FetchedModels fetchedModels = modelFetcher.getModels(connection, setup);

        progress.progress(NbStrings.getParsingModel());

        ProjectModelParser parser = new ProjectModelParser(project, modelFetcher);
        return parser.parseModel(fetchedModels);
    }

    private static File getProjectDirFromModels(FetchedProjectModels projectModels) {
        return projectModels
                .getProjectDef()
                .getMainProject()
                .getGenericProperties()
                .getProjectDir();
    }

    private static final class ProjectModelParser {
        private final List<NbGradleExtensionRef> extensions;
        private final ProjectModelFetcher modelFetcher;
        private final ExtensionModelCache cache;
        private final List<ModelLoadIssue> issues;
        private final Map<String, ModelLoadResult> modelLoadResultCache;

        public ProjectModelParser(NbGradleProject mainProject, ProjectModelFetcher modelFetcher) {
            this.extensions = mainProject.getExtensionRefs();
            this.modelFetcher = modelFetcher;
            this.cache = new ExtensionModelCache();
            this.issues = new LinkedList<ModelLoadIssue>();
            this.modelLoadResultCache = CollectionUtils.newHashMap(extensions.size());
        }

        private void addProjectInfoResults(
                FetchedProjectModels projectModels,
                NbGradleExtensionRef extension,
                List<Object> results) {

            List<BuilderResult> builderResults
                    = projectModels.getProjectInfoResults().get(extension.getName());
            if (builderResults == null) {
                return;
            }

            for (BuilderResult builderResult: builderResults) {
                BuilderIssue issue = builderResult.getIssue();
                if (issue != null) {
                    issues.add(ModelLoadIssues.builderError(projectModels, extension, issue));
                }

                Object resultObject = builderResult.getResultObject();
                if (resultObject != null) {
                    results.add(resultObject);
                }
            }
        }

        private Map<String, Lookup> createLookups(FetchedProjectModels projectModels) {
            GenericProjectProperties genericProperties
                    = projectModels.getProjectDef().getMainProject().getGenericProperties();

            Map<String, Lookup> result = CollectionUtils.newHashMap(extensions.size());
            for (NbGradleExtensionRef extension: extensions) {
                String extensionName = extension.getName();

                List<Object> models = new ArrayList<Object>();
                addProjectInfoResults(projectModels, extension, models);
                addAllNullSafe(models, modelFetcher.getToolingModelsForExtension(extension, projectModels));
                models.add(genericProperties);

                result.put(extensionName, Lookups.fixed(models.toArray()));
            }

            return result;
        }

        private ModelLoadResult getModelLoadResult(
                NbGradleExtensionRef extension,
                File defaultProjectDir,
                Map<File, ProjectModelsOfExtensions> extensionModels) {

            Map<File, Lookup> lookups = CollectionUtils.newHashMap(extensionModels.size());

            String extensionName = extension.getName();
            for (Map.Entry<File, ProjectModelsOfExtensions> entry: extensionModels.entrySet()) {
                Lookup lookup = entry.getValue().getExtensionLookups().get(extensionName);
                if (lookup == null) {
                    lookup = Lookup.EMPTY;
                }
                lookups.put(entry.getKey(), lookup);
            }

            return new ModelLoadResult(defaultProjectDir, lookups);
        }

        public Result parseModel(FetchedModels fetchedModels) {
            ProjectModelsOfExtensions extensionsForDefault = new ProjectModelsOfExtensions(
                    this,
                    fetchedModels.getDefaultProjectModels());

            File defaultProjectDir = extensionsForDefault.getProjectDir();

            Collection<FetchedProjectModels> otherProjectModels = fetchedModels.getOtherProjectModels();

            Map<File, ProjectModelsOfExtensions> extensionModels
                    = CollectionUtils.newHashMap(otherProjectModels.size());
            for (FetchedProjectModels models: otherProjectModels) {
                File projectDir = getProjectDirFromModels(models);
                if (defaultProjectDir.equals(projectDir)) {
                    continue;
                }

                extensionModels.put(projectDir, new ProjectModelsOfExtensions(this, models));
            }

            extensionModels.put(defaultProjectDir, extensionsForDefault);

            NbGradleModel mainModel = parseModel(
                    fetchedModels.getDefaultProjectModels(),
                    extensionModels);

            List<NbGradleModel> otherModels = new ArrayList<NbGradleModel>();
            for (FetchedProjectModels models: otherProjectModels) {
                File projectDir = getProjectDirFromModels(models);
                if (defaultProjectDir.equals(projectDir)) {
                    continue;
                }
                otherModels.add(parseModel(models, extensionModels));
            }

            return new Result(mainModel, otherModels, issues);
        }

        private NbGradleModel parseModel(
                FetchedProjectModels projectModels,
                Map<File, ProjectModelsOfExtensions> extensionModels) {
            Throwable issue = projectModels.getIssue();
            if (issue != null) {
                issues.add(ModelLoadIssues.projectModelLoadError(projectModels, issue));
            }

            NbGradleMultiProjectDef projectDef = new NbGradleMultiProjectDef(projectModels.getProjectDef());
            NbGenericModelInfo genericInfo = new NbGenericModelInfo(projectDef, modelFetcher.getSettingsFile());
            NbGradleModel.Builder result = new NbGradleModel.Builder(genericInfo);

            File projectDir = genericInfo.getProjectDir();

            ProjectExtensionModelCache projectCache = cache.tryGetProjectCache(projectDir);

            for (NbGradleExtensionRef extension: extensions) {
                String extensionName = extension.getName();

                CachedModel cachedModel = projectCache != null
                        ? projectCache.tryGetModel(extensionName)
                        : null;

                Object extensionModel;
                if (cachedModel != null) {
                    extensionModel = cachedModel.model;
                }
                else {
                    ModelLoadResult modelLoadResult = modelLoadResultCache.get(extensionName);
                    if (modelLoadResult == null) {
                        modelLoadResult = getModelLoadResult(extension, projectDir, extensionModels);
                        modelLoadResultCache.put(extensionName, modelLoadResult);
                    }
                    else {
                        modelLoadResult = modelLoadResult.withMainProject(projectDir);
                    }

                    ParsedModel<?> parsedModels = extension.parseModel(modelLoadResult);
                    extensionModel = parsedModels.getMainModel();

                    for (Map.Entry<File, ?> entry: parsedModels.getOtherProjectsModel().entrySet()) {
                        cache.getProjectCache(entry.getKey()).addModel(extensionName, entry.getValue());
                    }
                }

                result.setModelForExtension(extension, extensionModel);
            }

            return result.create();
        }
    }

    private static final class ProjectModelsOfExtensions {
        private final File projectDir;
        private final Map<String, Lookup> extensionLookups;

        public ProjectModelsOfExtensions(ProjectModelParser parser, FetchedProjectModels projectModels) {
            extensionLookups = parser.createLookups(projectModels);
            projectDir = getProjectDirFromModels(projectModels);
        }

        public File getProjectDir() {
            return projectDir;
        }

        public Map<String, Lookup> getExtensionLookups() {
            return extensionLookups;
        }
    }

    private static final class CachedModel {
        public final Object model;

        public CachedModel(Object model) {
            this.model = model;
        }
    }

    private static final class ProjectExtensionModelCache {
        private final Map<String, CachedModel> models;

        public ProjectExtensionModelCache() {
            this.models = new HashMap<String, CachedModel>();
        }

        public CachedModel tryGetModel(String extensionName) {
            return models.get(extensionName);
        }

        public void addModel(String extensionName, Object model) {
            models.put(extensionName, new CachedModel(model));
        }
    }

    private static final class ExtensionModelCache {
        private final Map<File, ProjectExtensionModelCache> projectCaches;

        public ExtensionModelCache() {
            this.projectCaches = new HashMap<File, ProjectExtensionModelCache>();
        }

        public ProjectExtensionModelCache tryGetProjectCache(File projectDir) {
            return projectCaches.get(projectDir);
        }

        public ProjectExtensionModelCache getProjectCache(File projectDir) {
            ProjectExtensionModelCache cache = projectCaches.get(projectDir);
            if (cache == null) {
                cache = new ProjectExtensionModelCache();
                projectCaches.put(projectDir, cache);
            }
            return cache;
        }
    }

    private static final class ProjectModelFetcher {
        private final File settingsFile;
        private final Map<String, List<Class<?>>> toolingModelNeeds;
        private final GenericModelFetcher modelFetcher;

        public ProjectModelFetcher(NbGradleProject project, GradleTarget gradleTarget) {
            this.settingsFile = NbGenericModelInfo.findSettingsGradle(project.getProjectDirectoryAsFile());

            List<NbGradleExtensionRef> extensions = project.getExtensionRefs();
            this.toolingModelNeeds = CollectionUtils.newHashMap(extensions.size());

            Map<Object, List<GradleBuildInfoQuery<?>>> buildInfoRequests = Collections.emptyMap();

            Map<Object, List<GradleProjectInfoQuery<?>>> projectInfoRequests
                    = new HashMap<Object, List<GradleProjectInfoQuery<?>>>();

            List<Class<?>> models = new LinkedList<Class<?>>();
            for (NbGradleExtensionRef extensionRef: extensions) {
                String extensionName = extensionRef.getName();

                GradleModelDefQuery2 modelQuery = getModelQuery(extensionRef.getExtensionDef());
                GradleModelDef modelDef = modelQuery.getModelDef(gradleTarget);

                models.addAll(modelDef.getToolingModels());
                addAllToMultiMap(extensionName, modelDef.getProjectInfoQueries(), projectInfoRequests);
                addAllToMultiMap(extensionName, modelDef.getToolingModels(), toolingModelNeeds);
            }

            modelFetcher = new GenericModelFetcher(buildInfoRequests, projectInfoRequests, models);
        }

        public FetchedModels getModels(ProjectConnection connection, OperationInitializer init) throws IOException {
            return modelFetcher.getModels(connection, init);
        }

        public File getSettingsFile() {
            return settingsFile;
        }

        public List<Object> getToolingModelsForExtension(
                NbGradleExtensionRef extension,
                FetchedProjectModels projectModels) {

            List<Class<?>> needs = toolingModelNeeds.get(extension.getName());
            if (needs == null || needs.isEmpty()) {
                return Collections.emptyList();
            }

            Map<Class<?>, Object> allModels = projectModels.getToolingModels();
            List<Object> result = new ArrayList<Object>(needs.size());
            for (Class<?> need: needs) {
                Object model = allModels.get(need);
                if (model != null) {
                    result.add(model);
                }
            }
            return result;
        }
    }
}
