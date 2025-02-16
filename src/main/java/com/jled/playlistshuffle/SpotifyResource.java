package com.jled.playlistshuffle;

import java.util.Objects;

public abstract class SpotifyResource {

  private String name;
  private String id;
  private boolean includeInShuffle;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public boolean isIncludeInShuffle() {
    return includeInShuffle;
  }

  public void setIncludeInShuffle(boolean includeInShuffle) {
    this.includeInShuffle = includeInShuffle;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;
    SpotifyResource that = (SpotifyResource) o;
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
