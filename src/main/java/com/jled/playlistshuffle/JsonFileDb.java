package com.jled.playlistshuffle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class JsonFileDb {
    private static final String FILE_PATH = "data.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    // Loads the entire Config object
    public static Config load() throws IOException {
        File file = new File(FILE_PATH);
        if (!file.exists()) return new Config(); // Return empty config if no file exists
        return mapper.readValue(file, new TypeReference<>() {});
    }

    // Saves the entire Config object
    public static void save(Config config) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(FILE_PATH), config);
    }

    // Loads only the playlists from the config
    public static List<Playlist> loadPlaylists() throws IOException {
        return load().getPlaylists();
    }

    // Loads only the artists from the config
    public static List<Artist> loadArtists() throws IOException {
        return load().getArtists();
    }

    // Saves the playlists by updating the config and re-saving it
    public static void savePlaylists(List<Playlist> playlists) throws IOException {
        Config config = load(); // Load current configuration
        config.setPlaylists(playlists); // Update playlists
        save(config); // Save updated config
    }

    // Saves the artists by updating the config and re-saving it
    public static void saveArtists(List<Artist> artists) throws IOException {
        Config config = load(); // Load current configuration
        config.setArtists(artists); // Update artists
        save(config); // Save updated config
    }
}