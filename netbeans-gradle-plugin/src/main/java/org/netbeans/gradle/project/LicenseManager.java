package org.netbeans.gradle.project;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.gradle.project.properties.LicenseHeaderInfo;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.RequestProcessor;

public final class LicenseManager {
    private static final Logger LOGGER = Logger.getLogger(LicenseManager.class.getName());

    private static final RequestProcessor SYNC_EXECUTOR
            = new RequestProcessor("LicenseManager-Processor", 1, true);

    // The key File does not contain a path we only use File to properly
    // use case insensitivity if required.
    private final Map<File, AtomicInteger> useCount;

    public LicenseManager() {
        this.useCount = new HashMap<File, AtomicInteger>();
    }

    private FileObject getLicenseRoot() {
        FileObject configRoot = FileUtil.getConfigRoot();
        return configRoot != null
                ? configRoot.getFileObject("Templates/Licenses")
                : null;
    }

    private void removeLicense(File file) throws IOException {
        assert SYNC_EXECUTOR.isRequestProcessorThread();

        FileObject licenseRoot = getLicenseRoot();
        if (licenseRoot == null) {
            LOGGER.warning("License root does not exist.");
            return;
        }

        FileObject licenseFile = licenseRoot.getFileObject(file.getPath());
        if (licenseFile == null) {
            LOGGER.log(Level.INFO, "License file does not exist: {0}", file);
            return;
        }
        licenseFile.delete();
    }

    private void addLicense(File file, NbGradleProject project, LicenseHeaderInfo header) throws IOException {
        assert SYNC_EXECUTOR.isRequestProcessorThread();

        FileObject licenseRoot = getLicenseRoot();
        if (licenseRoot == null) {
            LOGGER.warning("License root does not exist.");
            return;
        }

        if (licenseRoot.getFileObject(file.getPath()) != null) {
            LOGGER.log(Level.INFO, "License file already exists: {0}", file);
            return;
        }

        project.tryWaitForLoadedProject();
        File licenseTemplateFile = FileUtil.normalizeFile(header.getLicenseTemplateFile(project));
        if (licenseTemplateFile == null) {
            return;
        }

        FileObject licenseTemplateSrc = FileUtil.toFileObject(licenseTemplateFile);
        if (licenseTemplateSrc == null) {
            LOGGER.log(Level.WARNING, "Missing license template file: {0}", licenseTemplateFile);
            return;
        }

        licenseTemplateSrc.copy(licenseRoot, file.getPath(), "");
    }

    private void doUnregister(final File file) {
        SYNC_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                AtomicInteger fileCount = useCount.get(file);
                if (fileCount == null) {
                    LOGGER.log(Level.WARNING, "Too many unregister call to LicenseManager.", new Exception());
                    return;
                }

                if (fileCount.decrementAndGet() == 0) {
                    useCount.remove(file);
                    try {
                        removeLicense(file);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                }
            }
        });
    }

    private void doRegister(final File file, final NbGradleProject project, final LicenseHeaderInfo header) {
        SYNC_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                AtomicInteger fileCount = useCount.get(file);
                if (fileCount == null) {
                    fileCount = new AtomicInteger(1);
                    useCount.put(file, fileCount);
                }
                else {
                    fileCount.incrementAndGet();
                }
                try {
                    if (fileCount.get() == 1) {
                        addLicense(file, project, header);
                    }
                } catch (IOException ex) {
                    throw new RuntimeException(ex.getMessage(), ex);
                }
            }
        });
    }

    public Ref registerLicense(NbGradleProject project, LicenseHeaderInfo header) {
        if (project == null) throw new NullPointerException("project");
        if (header == null) {
            return NoOpRef.INSTANCE;
        }

        if (header.getLicenseTemplateFile() == null) {
            return NoOpRef.INSTANCE;
        }

        final File licenseFile = getLicenseFileName(project, header);
        doRegister(licenseFile, project, header);

        return new Ref() {
            private final AtomicBoolean unregistered = new AtomicBoolean(false);

            @Override
            public void unregister() {
                if (unregistered.compareAndSet(false, true)) {
                    doUnregister(licenseFile);
                }
            }
        };
    }

    private static File getLicenseFileName(NbGradleProject project, LicenseHeaderInfo header) {
        return new File("license-" + header.getPrivateLicenseName(project) + ".txt");
    }

    private enum NoOpRef implements Ref {
        INSTANCE;

        @Override
        public void unregister() {
        }
    }

    public static interface Ref {
        public void unregister();
    }
}
