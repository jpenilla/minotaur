package com.modrinth.minotaur;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.modrinth.minotaur.dependencies.Dependency;
import com.modrinth.minotaur.responses.ResponseUpload;
import masecla.modrinth4j.endpoints.version.CreateVersion.CreateVersionRequest;
import masecla.modrinth4j.main.ModrinthAPI;
import masecla.modrinth4j.model.version.ProjectVersion;
import masecla.modrinth4j.model.version.ProjectVersion.ProjectDependency;
import masecla.modrinth4j.model.version.ProjectVersion.VersionType;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.plugins.PluginManager;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;

import static com.modrinth.minotaur.Util.*;

/**
 * A task used to communicate with Modrinth for the purpose of uploading build artifacts.
 */
public class TaskModrinthUpload extends DefaultTask {
	/**
	 * The response from the API when the file was uploaded successfully.
	 */
	@Nullable
	public ProjectVersion newVersion = null;

	/**
	 * The response from the API when the file was uploaded successfully.
	 *
	 * @deprecated Please use {@link #newVersion} instead
	 */
	@SuppressWarnings("DeprecatedIsStillUsed")
	@Nullable
	@Deprecated
	@ApiStatus.ScheduledForRemoval(inVersion = "3.0.0")
	public ResponseUpload uploadInfo = null;

	/**
	 * Checks if the upload was successful or not.
	 *
	 * @return Whether the file was successfully uploaded.
	 * @deprecated This check should be done manually
	 */
	@SuppressWarnings("unused")
	@Deprecated
	@ApiStatus.ScheduledForRemoval(inVersion = "3.0.0")
	public boolean wasUploadSuccessful() {
		return newVersion != null;
	}

	/**
	 * Defines what to do when the Modrinth upload task is invoked.
	 * <ol>
	 *   <li>Attempts to automatically resolve various metadata items if not specified, throwing an exception if some
	 *   things still don't have anything set</li>
	 *   <li>Resolves each file or task to be uploaded, ensuring they're all valid</li>
	 *   <li>Uploads these files to the Modrinth API under a new version</li>
	 * </ol>
	 * This is all in a try/catch block so that, if {@link ModrinthExtension#getFailSilently()} is enabled, it won't
	 * fail the build if it fails to upload the version to Modrinth.
	 */
	@TaskAction
	public void apply() {
		getLogger().lifecycle("Minotaur: {}", getClass().getPackage().getImplementationVersion());
		ModrinthExtension ext = ext(getProject());
		PluginManager pluginManager = getProject().getPluginManager();
		try {
			ModrinthAPI api = api(getProject());

			String id = Objects.requireNonNull(api.projects().getProjectIdBySlug(ext.getProjectId().get()).join());
			getLogger().debug("Uploading version to project {}", id);

			// Add version name if it's null
			String versionNumber = resolveVersionNumber(getProject());
			if (ext.getVersionName().getOrNull() == null) {
				ext.getVersionName().set(versionNumber);
			}

			// Attempt to automatically resolve the loader if none were specified.
			if (ext.getLoaders().get().isEmpty() && ext.getDetectLoaders().get()) {
				Map<String, String> pluginLoaderMap = new HashMap<>();
				pluginLoaderMap.put("net.minecraftforge.gradle", "forge");
				pluginLoaderMap.put("fabric-loom", "fabric");
				pluginLoaderMap.put("org.quiltmc.loom", "quilt");
				pluginLoaderMap.put("org.spongepowered.gradle.plugin", "sponge");
				pluginLoaderMap.put("io.papermc.paperweight.userdev", "paper");

				pluginLoaderMap.forEach((plugin, loader) -> {
					if (pluginManager.hasPlugin(plugin)) {
						getLogger().debug("Adding loader {} because plugin {} was found.", loader, plugin);
						ext.getLoaders().add(loader);
					}
				});
			}

			if (ext.getLoaders().get().isEmpty()) {
				throw new GradleException("Cannot upload to Modrinth: no loaders specified!");
			}

			// Attempt to automatically resolve the game version if none were specified.
			if (ext.getGameVersions().get().isEmpty()) {
				if (ext.getLoaders().get().contains("forge")) {
					if (pluginManager.hasPlugin("net.minecraftforge.gradle")) {
						// ForgeGradle will store the game version here.
						// https://github.com/MinecraftForge/ForgeGradle/blob/FG_5.0/src/userdev/java/net/minecraftforge/gradle/userdev/MinecraftUserRepo.java#L199
						String version = (String) getProject().getExtensions().getExtraProperties().get("MC_VERSION");
						assert version != null;

						getLogger().debug("Adding fallback game version {} from ForgeGradle.", version);
						ext.getGameVersions().add(version);
					}
				}

				if (ext.getLoaders().get().contains("fabric") || ext.getLoaders().get().contains("quilt")) {
					if (pluginManager.hasPlugin("fabric-loom") || pluginManager.hasPlugin("org.quiltmc.loom")) {
						// Use the same method Loom uses to get the version.
						// https://github.com/FabricMC/fabric-loom/blob/97f594da8e132c3d33cf39fe8d7cc0e76d84aeb6/src/main/java/net/fabricmc/loom/configuration/DependencyInfo.java#LL60C26-L60C56
						String version = getProject().getConfigurations().getByName("minecraft")
							.getDependencies().iterator().next().getVersion();
						assert version != null;

						getLogger().debug("Adding fallback game version {} from Loom.", version);
						ext.getGameVersions().add(version);
					}
				}
			}

			if (ext.getGameVersions().get().isEmpty()) {
				throw new GradleException("Cannot upload to Modrinth: no game versions specified!");
			}

			// Convert each of our proto-dependencies to a proper Modrinth4J ProjectDependency
			List<Dependency> protoDependencies = new ArrayList<>();
			List<ProjectDependency> dependencies = new ArrayList<>();
			protoDependencies.addAll(ext.getNamedDependenciesAsList());
			protoDependencies.addAll(ext.getDependencies().get());
			protoDependencies.stream().map(dependency -> dependency.toNew(api, ext)).forEach(dependencies::add);

			// Get each of the files, starting with the primary file
			List<File> files = new ArrayList<>();
			files.add(resolveFile(getProject(), ext.getUploadFile().get()));

			// Convert each of the Object files from the extension to a proper File
			ext.getAdditionalFiles().get().forEach(file -> {
				File resolvedFile = resolveFile(getProject(), file);

				// Ensure the file actually exists before trying to upload it.
				if (resolvedFile == null || !resolvedFile.exists()) {
					throw new GradleException("The upload file is missing or null. " + file);
				}

				files.add(resolvedFile);
			});

			// Start construction of the actual request!
			CreateVersionRequest data = CreateVersionRequest.builder()
				.projectId(id)
				.versionNumber(versionNumber)
				.name(ext.getVersionName().get())
				.changelog(ext.getChangelog().get().replaceAll("\r\n", "\n"))
				.versionType(VersionType.valueOf(ext.getVersionType().get().toUpperCase(Locale.ROOT)))
				.gameVersions(ext.getGameVersions().get())
				.loaders(ext.getLoaders().get())
				.dependencies(dependencies)
				.files(files)
				.build();

			// Return early in debug mode
			if (ext.getDebugMode().get()) {
				Gson gson = new GsonBuilder().setPrettyPrinting().create();
				getLogger().lifecycle("Full data to be sent for upload: {}", gson.toJson(data));
				getLogger().lifecycle("Minotaur debug mode is enabled. Not going to upload this version.");
				return;
			}

			// Execute the request
			ProjectVersion version = api.versions().createProjectVersion(data).join();
			newVersion = version;
			//noinspection deprecation
			uploadInfo = new ResponseUpload(version);

			getLogger().lifecycle(
				"Successfully uploaded version {} to {} ({}) as version ID {}. {}",
				newVersion.getVersionNumber(),
				ext.getProjectId().get(),
				id,
				newVersion.getId(),
				String.format(
					"%s/project/%s/version/%s",
					ext.getApiUrl().get().replaceFirst("-?api", "").replaceFirst("/?v2/?", "").replaceFirst("//\\.", "//"),
					id,
					newVersion.getId()
				)
			);
		} catch (Exception e) {
			if (ext.getFailSilently().get()) {
				getLogger().info("Failed to upload to Modrinth. Check logs for more info.");
				getLogger().error("Modrinth upload failed silently.", e);
			} else {
				throw new GradleException("Failed to upload file to Modrinth! " + e.getMessage(), e);
			}
		}
	}
}
