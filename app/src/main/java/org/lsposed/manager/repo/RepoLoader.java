/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.manager.repo;

import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import org.lsposed.manager.App;
import org.lsposed.manager.R;
import org.lsposed.manager.repo.model.OnlineModule;
import org.lsposed.manager.repo.model.Release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class RepoLoader {
    private static RepoLoader instance = null;
    private Map<String, OnlineModule> onlineModules = new HashMap<>();
    private Map<String, ModuleVersion> latestVersion = new ConcurrentHashMap<>();

    public static class ModuleVersion {
        public String versionName;
        public long versionCode;

        private ModuleVersion(long versionCode, String versionName) {
            this.versionName = versionName;
            this.versionCode = versionCode;
        }

        public boolean upgradable(long versionCode, String versionName) {
            return this.versionCode > versionCode || (this.versionCode == versionCode && !versionName.replace(' ', '_').equals(this.versionName));
        }

    }

    private final Path repoFile = Paths.get(App.getInstance().getFilesDir().getAbsolutePath(), "repo.json");
    private final Set<RepoListener> listeners = ConcurrentHashMap.newKeySet();
    private boolean repoLoaded = false;
    private static final String originRepoUrl = "https://modules.lsposed.org//";
    private static final String backupRepoUrl = "https://modules-blogcdn.lsposed.org/";

    private static final String secondBackupRepoUrl = "https://modules-cloudflare.lsposed.org/";
    private static String repoUrl = originRepoUrl;
    private final Resources resources = App.getInstance().getResources();
    private final String[] channels = resources.getStringArray(R.array.update_channel_values);

    public boolean isRepoLoaded() {
        return repoLoaded;
    }

    public static synchronized RepoLoader getInstance() {
        if (instance == null) {
            instance = new RepoLoader();
            App.getExecutorService().submit(() -> instance.loadLocalData(true));
        }
        return instance;
    }

    synchronized public void loadRemoteData() {
        // Keep repoLoaded=true during the refresh: the in-memory catalog
        // stays valid while we probe the mirrors, so listeners (the repo
        // list UI) keep showing the previous data instead of a blank
        // "loading" state. Both the success path (loadLocalData) and the
        // failure path below restore/confirm repoLoaded=true.
        IOException lastError = null;
        String[] candidateRepoUrls = {originRepoUrl, backupRepoUrl, secondBackupRepoUrl};
        for (String candidateRepoUrl : candidateRepoUrls) {
            String requestUrl = candidateRepoUrl + "modules.json";
            Log.e(App.TAG, "REPO_TRACE start url=" + requestUrl);
            try (var response = App.getOkHttpClient().newCall(
                    new Request.Builder().url(requestUrl).build()).execute()) {
                Log.e(App.TAG, "REPO_TRACE response url=" + response.request().url()
                        + " code=" + response.code() + " successful=" + response.isSuccessful());
                if (!response.isSuccessful()) {
                    throw new IOException("Repository HTTP " + response.code()
                            + " from " + response.request().url());
                }
                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("Repository returned an empty response body");
                }
                String bodyString = body.string();
                OnlineModule[] parsedModules = new Gson().fromJson(bodyString, OnlineModule[].class);
                if (parsedModules == null || parsedModules.length == 0) {
                    throw new IOException("Repository returned an empty or invalid module list");
                }
                for (OnlineModule module : parsedModules) {
                    if (module == null || module.getName() == null || module.getName().isEmpty()) {
                        throw new IOException("Repository module list contains an invalid entry");
                    }
                }

                Path temporaryRepoFile = repoFile.resolveSibling("repo.json.tmp");
                Files.write(temporaryRepoFile, bodyString.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(temporaryRepoFile, repoFile,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporaryRepoFile, repoFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                repoUrl = candidateRepoUrl;
                loadLocalData(false);
                return;
            } catch (Throwable error) {
                lastError = error instanceof IOException ? (IOException) error
                        : new IOException("Unable to load repository from " + candidateRepoUrl, error);
                Log.e(App.TAG, "REPO_TRACE failure url=" + candidateRepoUrl
                        + " exception=" + error.getClass().getName()
                        + " message=" + error.getMessage(), error);
                Log.e(App.TAG, "load remote data from " + candidateRepoUrl, error);
            }
        }

        if (lastError != null) {
            for (RepoListener listener : listeners) {
                listener.onThrowable(lastError);
            }
        }
        // Prefer the in-memory one (already parsed and shown), and only go
        // back to disk when nothing is loaded yet. loadLocalData() sets
        // repoLoaded=false on entry, so calling it here would blank the UI
        // for every listener that reads getOnlineModules() afterwards.
        if (!onlineModules.isEmpty()) {
            repoLoaded = true;
            for (RepoListener listener : listeners) {
                listener.onRepoLoaded();
            }
        } else if (Files.exists(repoFile)) {
            loadLocalData(false);
        } else {
            repoLoaded = true;
            for (RepoListener listener : listeners) {
                listener.onRepoLoaded();
            }
        }
    }

    synchronized public void loadLocalData(boolean updateRemoteRepo) {
        repoLoaded = false;
        try {
            if (Files.notExists(repoFile)) {
                loadRemoteData();
                updateRemoteRepo = false;
            }
            byte[] encoded = Files.readAllBytes(repoFile);
            String bodyString = new String(encoded, StandardCharsets.UTF_8);
            Gson gson = new Gson();
            Map<String, OnlineModule> modules = new HashMap<>();
            OnlineModule[] repoModules = gson.fromJson(bodyString, OnlineModule[].class);
            Arrays.stream(repoModules).forEach(onlineModule -> modules.put(onlineModule.getName(), onlineModule));
            var channel = App.getPreferences().getString("update_channel", channels[0]);
            updateLatestVersion(repoModules, channel);
            onlineModules = modules;
        } catch (Throwable t) {
            Log.e(App.TAG, Log.getStackTraceString(t));
            for (RepoListener listener : listeners) {
                listener.onThrowable(t);
            }
        } finally {
            repoLoaded = true;
            for (RepoListener listener : listeners) {
                listener.onRepoLoaded();
            }
            if (updateRemoteRepo) loadRemoteData();
        }
    }

    synchronized private void updateLatestVersion(OnlineModule[] onlineModules, String channel) {
        repoLoaded = false;
        Map<String, ModuleVersion> versions = new ConcurrentHashMap<>();
        for (var module : onlineModules) {
            String release = module.getLatestRelease();
            if (channel.equals(channels[1]) && module.getLatestBetaRelease() != null && !module.getLatestBetaRelease().isEmpty()) {
                release = module.getLatestBetaRelease();
            } else if (channel.equals(channels[2])) {
                if (module.getLatestSnapshotRelease() != null && !module.getLatestSnapshotRelease().isEmpty())
                    release = module.getLatestSnapshotRelease();
                else if (module.getLatestBetaRelease() != null && !module.getLatestBetaRelease().isEmpty())
                    release = module.getLatestBetaRelease();
            }
            if (release == null || release.isEmpty()) continue;
            var splits = release.split("-", 2);
            if (splits.length < 2) continue;
            long verCode;
            String verName;
            try {
                verCode = Long.parseLong(splits[0]);
                verName = splits[1];
            } catch (NumberFormatException ignored) {
                continue;
            }
            String pkgName = module.getName();
            versions.put(pkgName, new ModuleVersion(verCode, verName));
        }
        latestVersion = versions;
        repoLoaded = true;
        for (RepoListener listener : listeners) {
            listener.onRepoLoaded();
        }
    }

    public void updateLatestVersion(String channel) {
        if (repoLoaded)
            updateLatestVersion(onlineModules.keySet().parallelStream().map(onlineModules::get).toArray(OnlineModule[]::new), channel);
    }

    @Nullable
    public ModuleVersion getModuleLatestVersion(String packageName) {
        return repoLoaded ? latestVersion.getOrDefault(packageName, null) : null;
    }

    @Nullable
    public List<Release> getReleases(String packageName) {
        var channel = App.getPreferences().getString("update_channel", channels[0]);
        List<Release> releases = new ArrayList<>();
        if (repoLoaded) {
            var module = onlineModules.get(packageName);
            if (module != null) {
                releases = module.getReleases();
                if (!module.releasesLoaded) {
                    if (channel.equals(channels[1]) && !(module.getBetaReleases() != null && module.getBetaReleases().isEmpty())) {
                        releases = module.getBetaReleases();
                    } else if (channel.equals(channels[2]))
                        if (!(module.getSnapshotReleases() != null && module.getSnapshotReleases().isEmpty()))
                            releases = module.getSnapshotReleases();
                        else if (!(module.getBetaReleases() != null && module.getBetaReleases().isEmpty()))
                            releases = module.getBetaReleases();
                }
            }
        }
        return releases;
    }

    @Nullable
    public String getLatestReleaseTime(String packageName, String channel) {
        String releaseTime = null;
        if (repoLoaded) {
            var module = onlineModules.get(packageName);
            if (module != null) {
                releaseTime = module.getLatestReleaseTime();
                if (channel.equals(channels[1]) && module.getLatestBetaReleaseTime() != null) {
                    releaseTime = module.getLatestBetaReleaseTime();
                } else if (channel.equals(channels[2]))
                    if (module.getLatestSnapshotReleaseTime() != null)
                        releaseTime = module.getLatestSnapshotReleaseTime();
                    else if (module.getLatestBetaReleaseTime() != null)
                        releaseTime = module.getLatestBetaReleaseTime();
            }
        }
        return releaseTime;
    }

    public void loadRemoteReleases(String packageName) {
        App.getOkHttpClient().newCall(new Request.Builder().url(String.format(repoUrl + "module/%s.json", packageName)).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(App.TAG, call.request().url() + e.getMessage());
                if (repoUrl.equals(originRepoUrl)) {
                    repoUrl = backupRepoUrl;
                    loadRemoteReleases(packageName);
                } else if (repoUrl.equals(backupRepoUrl)) {
                    repoUrl = secondBackupRepoUrl;
                    loadRemoteReleases(packageName);
                } else {
                    for (RepoListener listener : listeners) {
                        listener.onThrowable(e);
                    }
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (!response.isSuccessful()) {
                    IOException error = new IOException("Repository HTTP " + response.code()
                            + " from " + response.request().url());
                    response.close();
                    if (repoUrl.equals(originRepoUrl)) {
                        repoUrl = backupRepoUrl;
                        loadRemoteReleases(packageName);
                    } else if (repoUrl.equals(backupRepoUrl)) {
                        repoUrl = secondBackupRepoUrl;
                        loadRemoteReleases(packageName);
                    } else {
                        for (RepoListener listener : listeners) {
                            listener.onThrowable(error);
                        }
                    }
                    return;
                }
                if (response.isSuccessful()) {
                    ResponseBody body = response.body();
                    if (body != null) {
                        try {
                            String bodyString = body.string();
                            Gson gson = new Gson();
                            OnlineModule module = gson.fromJson(bodyString, OnlineModule.class);
                            module.releasesLoaded = true;
                            onlineModules.replace(packageName, module);
                            for (RepoListener listener : listeners) {
                                listener.onModuleReleasesLoaded(module);
                            }
                        } catch (Throwable t) {
                            Log.e(App.TAG, Log.getStackTraceString(t));
                            for (RepoListener listener : listeners) {
                                listener.onThrowable(t);
                            }
                        }
                    }
                }
            }
        });
    }

    public void addListener(RepoListener listener) {
        listeners.add(listener);
    }

    public void removeListener(RepoListener listener) {
        listeners.remove(listener);
    }

    @Nullable
    public OnlineModule getOnlineModule(String packageName) {
        return repoLoaded && packageName != null ? onlineModules.get(packageName) : null;
    }

    @Nullable
    public Collection<OnlineModule> getOnlineModules() {
        return repoLoaded ? onlineModules.values() : null;
    }

    public interface RepoListener {
        default void onRepoLoaded() {
        }

        default void onModuleReleasesLoaded(OnlineModule module) {
        }

        default void onThrowable(Throwable t) {
            Log.e(App.TAG, "load repo failed", t);
        }
    }
}
