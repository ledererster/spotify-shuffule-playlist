package com.jled.playlistshuffle;

import java.util.ArrayList;
import java.util.List;

public class Config {

  List<Playlist> playlists = new ArrayList<>();
  List<Artist> artists = new ArrayList<>();

  public List<Playlist> getPlaylists() {
    return playlists;
  }

  public void setPlaylists(List<Playlist> playlists) {
    this.playlists = playlists;
  }

  public List<Artist> getArtists() {
    return artists;
  }

  public void setArtists(List<Artist> artists) {
    this.artists = artists;
  }
}
