package net.ccbluex.modloader.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Fabric's jars for one Minecraft version, fetched from Fabric's own servers and Modrinth, checked against the
 * SHA-512 the same server gives, and kept in a directory: a jar found there is used without asking the network.
 *
 * @agent claude-opus-5-5
 */
final class Downloads {

    private final Path directory;
    private final String minecraft;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    Downloads(Path directory, String minecraft) {
        this.directory = directory;
        this.minecraft = minecraft;
    }

    /**
     * The newest stable Fabric Loader for the Minecraft version, first, then the libraries its launcher profile
     * puts on the class path.
     */
    List<Path> fabricLoader() throws IOException {
        Path jars = directory.resolve("fabric-loader");
        if (!Files.isDirectory(jars)) {
            String version = null;
            for (JsonElement entry : json("https://meta.fabricmc.net/v2/versions/loader/" + minecraft).getAsJsonArray()) {
                JsonObject loader = entry.getAsJsonObject().getAsJsonObject("loader");
                if (version == null && loader.get("stable").getAsBoolean()) {
                    version = loader.get("version").getAsString();
                }
            }
            if (version == null) {
                throw new IOException("Fabric has no stable loader for Minecraft " + minecraft);
            }
            Files.createDirectories(directory);
            // Filled apart and moved in whole: the directory stands for a complete download.
            Path part = Files.createTempDirectory(directory, "fabric-loader");
            JsonObject profile = json("https://meta.fabricmc.net/v2/versions/loader/" + minecraft + "/" + version + "/profile/json").getAsJsonObject();
            for (JsonElement entry : profile.getAsJsonArray("libraries")) {
                JsonObject library = entry.getAsJsonObject();
                String[] name = library.get("name").getAsString().split(":");
                String url = library.get("url").getAsString() + name[0].replace('.', '/') + "/" + name[1] + "/" + name[2] + "/"
                        + name[1] + "-" + name[2] + ".jar";
                // The file holds the hash, sometimes followed by the file name.
                download(url, text(url + ".sha512").strip().split("\\s+")[0], part.resolve(name[1] + ".jar"));
            }
            Files.move(part, jars, StandardCopyOption.ATOMIC_MOVE);
        }
        try (Stream<Path> files = Files.list(jars)) {
            return files.sorted(Comparator.comparing(file -> !file.getFileName().toString().equals("fabric-loader.jar"))).toList();
        }
    }

    /** The newest release of a Modrinth project for Fabric on the Minecraft version, else its newest version. */
    Path modrinth(String project) throws IOException {
        Path jar = directory.resolve(project + ".jar");
        if (Files.exists(jar)) {
            return jar;
        }
        JsonArray versions = json("https://api.modrinth.com/v2/project/" + project + "/version?loaders="
                + URLEncoder.encode("[\"fabric\"]", StandardCharsets.UTF_8) + "&game_versions="
                + URLEncoder.encode("[\"" + minecraft + "\"]", StandardCharsets.UTF_8)).getAsJsonArray();
        if (versions.isEmpty()) {
            throw new IOException("Modrinth has no " + project + " for Minecraft " + minecraft);
        }
        // Newest first.
        JsonObject chosen = versions.get(0).getAsJsonObject();
        for (JsonElement version : versions) {
            if (version.getAsJsonObject().get("version_type").getAsString().equals("release")) {
                chosen = version.getAsJsonObject();
                break;
            }
        }
        JsonObject file = chosen.getAsJsonArray("files").get(0).getAsJsonObject();
        for (JsonElement each : chosen.getAsJsonArray("files")) {
            if (each.getAsJsonObject().get("primary").getAsBoolean()) {
                file = each.getAsJsonObject();
            }
        }
        return download(file.get("url").getAsString(), file.getAsJsonObject("hashes").get("sha512").getAsString(), jar);
    }

    private Path download(String url, String sha512, Path jar) throws IOException {
        System.out.println("[ModLoader] downloading " + url);
        byte[] bytes = get(url, HttpResponse.BodyHandlers.ofByteArray());
        try {
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes)).equalsIgnoreCase(sha512)) {
                throw new IOException(url + " does not match the checksum its server gives");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Files.createDirectories(jar.getParent());
        Path part = Files.createTempFile(jar.getParent(), jar.getFileName().toString(), ".part");
        Files.write(part, bytes);
        return Files.move(part, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private JsonElement json(String url) throws IOException {
        return JsonParser.parseString(text(url));
    }

    private String text(String url) throws IOException {
        return get(url, HttpResponse.BodyHandlers.ofString());
    }

    private <T> T get(String url, HttpResponse.BodyHandler<T> body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                // Modrinth asks every client to name itself.
                .header("User-Agent", "CCBlueX/ModLoader")
                .timeout(Duration.ofMinutes(2))
                .build();
        try {
            HttpResponse<T> response = http.send(request, body);
            if (response.statusCode() != 200) {
                throw new IOException(url + " answered " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
