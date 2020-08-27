//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS org.zeroturnaround:zt-exec:1.11
//DEPS com.github.docker-java:docker-java:3.2.5
//DEPS com.github.docker-java:docker-java-transport-okhttp:3.2.5
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.11.2
//DEPS org.slf4j:slf4j-api:1.7.30
//DEPS org.slf4j:slf4j-simple:1.7.30

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.PruneType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "build-images", mixinStandardHelpOptions = true,
        description = "build container images providing the `native-image` executable for Quarkus")
class BuildImages implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "A yaml file containing the configuration of the images to build.")
    private File config;

    public static void main(String... args) {
        int exitCode = new CommandLine(new BuildImages()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws IOException {

        if (!config.isFile()) {
            System.out.println("The configuration file " + config.getAbsolutePath() + " does not exist - exiting");
            return -1;
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        Configuration configuration = mapper.readValue(config, Configuration.class);
        configuration.validateConfiguration();

        // Preparation
        Docker docker = new Docker();

        // Build
        for (String version : configuration.versions) {
            docker.deleteExistingImageIfExists(configuration.imageName, version);

            try {
                build(version, configuration);
            } catch (Exception e) {
                String name = configuration.imageName + ":" + version;
                System.out.println("Build of image  " + name + " has failed: " + e.getMessage() + " - exiting");
                return -1;
            }
        }

        // Post-Validation
        if (!docker.validateCreatedImages(configuration)) {
            return -1;
        }

        if (!docker.createTags(configuration)) {
            return -1;
        }

        // Cleanup
        docker.prune();

        return 0;
    }

    private void build(String version, Configuration configuration) {
        int status;
        try {
            status = new ProcessExecutor(configuration.buildScript, version)
                    .redirectOutput(System.out)
                    .execute()
                    .getExitValue();
        } catch (Exception e) {
            throw new RuntimeException("Build Failed", e);
        }
        if (status != 0) {
            throw new RuntimeException("Build Failed with status " + status);
        }
    }

    class Docker {
        DockerClient client;

        Docker() {
            DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
            DockerHttpClient client = new OkDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .sslConfig(config.getSSLConfig())
                    .build();
            this.client = DockerClientImpl.getInstance(config, client);
        }
    
        boolean createTags(Configuration configuration) {
            for (Tag tag: configuration.tags) {
                String tagID = tag.id;
                // Look for image id
                String target = configuration.imageName + ":" + tag.target;
                List<Image> images = client.listImagesCmd().withImageNameFilter(target).exec();
                if (images.isEmpty()) {
                    System.out.println("Unable to tag " + tagID + " - target cannot be found " + target + " - exiting");
                    return false;
                } else if (images.size() > 1) {
                    System.out.println("Unable to tag " + tagID + " - multiple target matches " + images + "  - exiting");
                    return false;
                }
    
                Image image = images.get(0);
                client.tagImageCmd(image.getId(), target, tagID).exec();
                System.out.println("Tag " + tagID + " created, pointing to " + target);
            }
            return true;
        }
    
        boolean validateCreatedImages(Configuration configuration) {
            List<Image> images = client.listImagesCmd()
                    .withImageNameFilter(configuration.imageName)
                    .exec();
            for (String version : configuration.versions) {
                String expectedImageName = configuration.imageName + ":" + version;
                Optional<Image> found = images.stream()
                        .filter(i -> Arrays.asList(i.getRepoTags()).contains(expectedImageName)).findFirst();
                if (found.isPresent()) {
                    System.out.println("Image " + expectedImageName + " created!");
                } else {
                    System.out.println("Expected " + expectedImageName + " to be created, but cannot find it - exiting");
                    return false;
                }
            }
            return true;
        }
    
        void deleteExistingImageIfExists(String imageName, String version) {
            List<Image> images = client.listImagesCmd()
                    .withImageNameFilter(imageName)
                    .exec();
    
            String expectedImageName = imageName + ":" + version;
            images.stream().filter(i -> Arrays.asList(i.getRepoTags()).contains(expectedImageName))
                    .forEach(i -> {
                        System.out.println("Existing image found: " + expectedImageName + " : " + i.getId());
                        System.out.println("Deleting the existing image...");
                        client.removeImageCmd(i.getId()).withForce(true).withNoPrune(false).exec();
                    });
        }
    
        void prune() {
            client.pruneCmd(PruneType.IMAGES).exec();
        }
    
    }
    
    static class Configuration {
        @JsonProperty
        String image;
        @JsonProperty
        String imageName;
        @JsonProperty
        String buildScript;
        @JsonProperty
        List<String> versions;
        @JsonProperty
        List<Tag> tags;

        void validateConfiguration() {
            // Validation
            File image = new File(this.image);
            if (!image.isFile()) {
                System.out.println("The image descriptor " + image.getAbsolutePath() + " does not exist - exiting");
                System.exit(-1);
            }
            File buildScript = new File(this.buildScript);
            if (!image.isFile()) {
                System.out.println("The build script " + buildScript + " does not exist - exiting");
                System.exit(-1);
            }
            for (Tag tag : tags) {
                if (!versions.contains(tag.target)) {
                    System.out.println("A tag target on unknown version: " + tag.target + " - exiting");
                    System.exit(-1);
                }
                if (tag.id.equalsIgnoreCase(tag.target)) {
                    System.out.println("A tag name is the same as the target: " + tag.id + " - exiting");
                    System.exit(-1);
                }
            }
            for (String version : versions) {
                verifyVersion(version);
            }
        }

        private void verifyVersion(String version) {
            File module = new File("modules/mandrel/" + version);
            assert module.isDirectory();
        }
    }
    
    static class Tag {
        @JsonProperty
        String id;
        @JsonProperty
        String target;
    }
}

