package hudson.plugins.build_publisher;

import hudson.Util;
import hudson.XmlFile;
import hudson.maven.MavenModule;
import hudson.model.AbstractProject;
import hudson.model.Build;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Project;
import hudson.model.ProminentProjectAction;
import hudson.model.Run;
import hudson.security.Permission;
import hudson.tasks.ArtifactArchiver;
import hudson.util.IOException2;
import hudson.util.TextFile;
import net.sf.json.JSONObject;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.taskdefs.Untar;
import org.apache.tools.ant.types.Resource;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Recieves builds submitted remotely via HTTP.
 *
 * @author dvrzalik@redhat.com
 *
 */
public class ExternalProjectProperty extends JobProperty<Job<?, ?>> implements
        ProminentProjectAction {

    private static final Logger LOGGER = Logger.getLogger(ExternalProjectProperty.class.getName());

    private transient AbstractProject project;

    @Override
    public JobPropertyDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ProminentProjectAction getJobAction(Job<?, ?> job) {
        this.project = (AbstractProject) job;
        return this;
    }

    /**
     * Updates projects from submitted config.xml
     *
     * TODO: use POST to config.xml, which is new in Hudson 1.197 at some point 
     */
    public void doAcceptConfig(StaplerRequest req, StaplerResponse rsp)
            throws IOException {
        project.checkPermission(Permission.CONFIGURE);

        // Partially taken from Hudson.doCreateItem(..)
        XmlFile configXmlFile = project.getConfigFile();
        File configXml = configXmlFile.getFile();
        configXml.getParentFile().mkdirs();
        FileOutputStream fos = new FileOutputStream(configXml);
        try {
            Util.copyStream(req.getInputStream(), fos);
            project.onLoad(project.getParent(), project.getName());
            configXmlFile.unmarshal(project);
        } catch (IOException e) {
            LOGGER.severe("Failed to accept configuration for "
                    + project.getName() + e.getMessage());
            throw e;
        } finally {
            fos.close();
        }

    }

    /**
     * Accepts incoming MavenModule, provided that current project is
     * MavenModuleSet
     */
    public void doAcceptMavenModule(StaplerRequest req, StaplerResponse rsp)
            throws IOException {
        acceptChildProject(req, rsp, project, "modules");
    }
     
    /**
     * Accepts MatrixConfiguration
     */
    public void doAcceptMatrixConfiguration(StaplerRequest req, StaplerResponse rsp)
            throws IOException {
        acceptChildProject(req, rsp, project, "configurations");
    }
    
    /**
     * Accepts nested project (like maven module or matrix configuration).
     */
    private static void acceptChildProject(StaplerRequest req, StaplerResponse rsp,
            AbstractProject project, String subDir)
            throws IOException {
        project.checkPermission(Permission.CONFIGURE);

        String name = req.getParameter("name").trim();
        File modulesDir = new File(project.getRootDir(), subDir);
        File moduleDir = new File(modulesDir, name);
        moduleDir.mkdirs();
        File configFile = new File(moduleDir, "config.xml");

        try {
            FileOutputStream fos = new FileOutputStream(configFile);
            try {
                Util.copyStream(req.getInputStream(), fos);

                project.onLoad(project.getParent(), project.getName());
                
                //Add this property to the child project, otherwise it won't be able to recieve builds
                Job item = (Job) ((ItemGroup) project).getItem(name);
                if(item.getProperty(ExternalProjectProperty.class) == null) {
                    item.addProperty(new ExternalProjectProperty());       
                }
            } finally {
                fos.close();
            }

        } catch (IOException e) {
            LOGGER.severe("Failed to accept child project " + name
                    + " for " + project.getName() + e.getMessage());

            // This is questionable
            // Util.deleteRecursive(moduleDir);

            throw e;
        }
    }

    /**
     * "Collecting basket" for incoming builds.
     */
    public void doAcceptBuild(StaplerRequest req, StaplerResponse rsp)
            throws IOException {
        project.checkPermission(Permission.CONFIGURE);

        // Don't send notifications for old builds
        Set<String> oldBuildIDs = new HashSet<String>();
        for (Run run : (List<Run>) project.getBuilds()) {
            oldBuildIDs.add(run.getId());
        }

        //Untar incoming builds unto the build directory
        Untar untar = new Untar();
        untar.setProject(new org.apache.tools.ant.Project());
        untar.add(new InputStreamResource(project.getName(),
                new BufferedInputStream(req.getInputStream())));
        untar.setDest(new File(project.getRootDir(), "builds"));
        untar.setOverwrite(true);

        try {
            untar.execute();
            
            //Load incoming builds from disk
            reloadProject(project);
            
            //Remove publishing status actions (so that they don't confuse users).
            //We don't know which (or how many) builds arrive - need to check them all
            for(Run build: (List<Run>) project.getBuilds()) {
                StatusAction statusAction = build.getAction(StatusAction.class);
                if(statusAction != null) {
                    build.getActions().remove(statusAction);
                    build.save();
                }
            }
            
            //Update next build number
            int nextBuildNumber = project.getLastBuild().number + 1;
            TextFile f = new TextFile(new File(project.getRootDir(),
                    "nextBuildNumber"));
            f.write(String.valueOf(nextBuildNumber));
            // Second reload just because of the build number. :(
            reloadProject(project);

            try {
                tidyUp();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Cleaning project " + project.getName()
                        + "failed: " + e.getMessage(),e);
            }

        } catch (BuildException e) {
            LOGGER.log(Level.SEVERE, "Failed to read the remote stream "
                    + project.getName() + e.getMessage(),e);
            throw new IOException2("Failed to read the remote stream "
                    + project.getName(), e);
        }
    }

    private void tidyUp() throws IOException {
        // delete old builds
        project.logRotate();

        // keep artifacts of last successful build only
        // (taken from ArtifactArchiver)
        if (project instanceof Project) {
            ArtifactArchiver archiver = (ArtifactArchiver) ((Project<?, ?>) project)
                    .getPublisher(ArtifactArchiver.DESCRIPTOR);
            if ((archiver != null) && archiver.isLatestOnly()) {
                Build<?, ?> build = ((Project<?, ?>) project)
                        .getLastSuccessfulBuild();
                if (build != null) {
                    while (true) {
                        build = build.getPreviousBuild();
                        if (build == null)
                            break;

                        // remove old artifacts
                        File ad = build.getArtifactsDir();
                        if (ad.exists()) {
                            LOGGER.info("Deleting old artifacts from "
                                    + build.getDisplayName());
                            Util.deleteRecursive(ad);
                        }
                    }
                }
            }
        }

    }

    private static void reloadProject(AbstractProject project)
            throws IOException {
        if (project instanceof MavenModule) {
            project.onLoad(project.getParent(), ((MavenModule) project)
                    .getModuleName().toFileSystemName());
        } else {
            project.onLoad(project.getParent(), project.getName());
        }
    }

    private static class InputStreamResource extends Resource {
        private final InputStream in;

        public InputStreamResource(String name, InputStream in) {
            this.in = in;
            setName(name);
        }

        public InputStream getInputStream() throws IOException {
            return in;
        }
    }

    /*
     * Descriptor, etc..
     */

    public static final ExternalProjectPropertyDescriptor DESCRIPTOR = new ExternalProjectPropertyDescriptor();

    public static class ExternalProjectPropertyDescriptor extends
            JobPropertyDescriptor {

        public ExternalProjectPropertyDescriptor() {
            super(ExternalProjectProperty.class);
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Post remote build";
        }

        @Override
        public JobProperty<?> newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return new ExternalProjectProperty();
        }

    }

    public String getDisplayName() {
        return DESCRIPTOR.getDisplayName();
    }

    public String getIconFileName() {
        return null;
    }

    public String getUrlName() {
        return "postBuild";
    }

}