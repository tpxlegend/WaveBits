package app.wavebits.host.android.youtube

import app.wavebits.innertube.models.ArtistItem
import app.wavebits.innertube.models.PlaylistItem
import app.wavebits.innertube.models.SongItem
import app.wavebits.innertube.pages.WaveBitsInnertubeParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLibrarySyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `cleanDataSyncId normalizes delegated and personal DATASYNC_ID tokens`() {
        assertEquals("gaia456", YouTubeAccount.cleanDataSyncId("delegated123||gaia456"))
        assertEquals("delegated123", YouTubeAccount.cleanDataSyncId("delegated123"))
        assertEquals("gaia456", YouTubeAccount.cleanDataSyncId("gaia456||"))
        assertEquals("gaia456", YouTubeAccount.cleanDataSyncId("||gaia456"))
        assertEquals("", YouTubeAccount.cleanDataSyncId("||"))
        assertEquals("", YouTubeAccount.cleanDataSyncId("   "))
    }

    @Test
    fun `parseLibrary normalizes MPLA library artist IDs and extracts inline grid continuation tokens`() {
        val raw = """
            {
              "contents": {
                "singleColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "selected": true,
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "itemSectionRenderer": {
                                  "contents": [
                                    {
                                      "gridRenderer": {
                                        "items": [
                                          {
                                            "musicTwoRowItemRenderer": {
                                              "title": { "runs": [{ "text": "Coldplay" }] },
                                              "navigationEndpoint": {
                                                "browseEndpoint": {
                                                  "browseId": "MPLAUC_coldplay123",
                                                  "browseEndpointContextSupportedConfigs": {
                                                    "browseEndpointContextMusicConfig": {
                                                      "pageType": "MUSIC_PAGE_TYPE_LIBRARY_ARTIST"
                                                    }
                                                  }
                                                }
                                              }
                                            }
                                          },
                                          {
                                            "continuationItemRenderer": {
                                              "continuationEndpoint": {
                                                "continuationCommand": {
                                                  "token": "CONT_TOKEN_ARTISTS_2"
                                                }
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val page = WaveBitsInnertubeParser.parseLibrary(json.parseToJsonElement(raw).jsonObject)
        val artists = page.items.filterIsInstance<ArtistItem>()
        assertEquals(1, artists.size)
        assertEquals("UC_coldplay123", artists.first().id)
        assertEquals("Coldplay", artists.first().title)
        assertEquals("CONT_TOKEN_ARTISTS_2", page.continuation)
    }

    @Test
    fun `parseLibrary combines section items with nested items in secondary containers`() {
        val raw = """
            {
              "contents": {
                "singleColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "selected": true,
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicCarouselShelfRenderer": {
                                  "header": {
                                    "musicCarouselShelfBasicHeaderRenderer": {
                                      "title": { "runs": [{ "text": "Your Likes" }] }
                                    }
                                  },
                                  "contents": [
                                    {
                                      "musicTwoRowItemRenderer": {
                                        "title": { "runs": [{ "text": "Liked Music" }] },
                                        "navigationEndpoint": {
                                          "browseEndpoint": {
                                            "browseId": "VLLM"
                                          }
                                        }
                                      }
                                    }
                                  ]
                                }
                              },
                              {
                                "musicShelfRenderer": {
                                  "contents": [
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": { "runs": [{ "text": "Road Trip Mix" }] }
                                            }
                                          }
                                        ],
                                        "navigationEndpoint": {
                                          "browseEndpoint": {
                                            "browseId": "VLPL_my_playlist_99",
                                            "browseEndpointContextSupportedConfigs": {
                                              "browseEndpointContextMusicConfig": {
                                                "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                                              }
                                            }
                                          }
                                        }
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val page = WaveBitsInnertubeParser.parseLibrary(json.parseToJsonElement(raw).jsonObject)
        val playlists = page.items.filterIsInstance<PlaylistItem>()
        assertEquals(2, playlists.size)
        assertTrue(playlists.any { it.id == "LM" })
        assertTrue(playlists.any { it.id == "PL_my_playlist_99" })
    }

    @Test
    fun `parsePlaylist parses Liked Music songs and inline shelf continuation token`() {
        val raw = """
            {
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicResponsiveHeaderRenderer": {
                                  "title": { "runs": [{ "text": "Your Likes" }] }
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ],
                  "secondaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "musicPlaylistShelfRenderer": {
                            "contents": [
                              {
                                "musicResponsiveListItemRenderer": {
                                  "playlistItemData": { "videoId": "dQw4w9WgXcQ" },
                                  "flexColumns": [
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "runs": [{ "text": "Never Gonna Give You Up" }] }
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "runs": [{ "text": "Rick Astley" }] }
                                      }
                                    }
                                  ]
                                }
                              },
                              {
                                "continuationItemRenderer": {
                                  "continuationEndpoint": {
                                    "continuationCommand": {
                                      "token": "CONT_LIKED_PAGE_2"
                                    }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val page = WaveBitsInnertubeParser.parsePlaylist(json.parseToJsonElement(raw).jsonObject, "LM")
        assertEquals("LM", page.playlist.id)
        assertEquals(1, page.songs.size)
        assertEquals("dQw4w9WgXcQ", page.songs.first().id)
        assertEquals("CONT_LIKED_PAGE_2", page.continuation)
    }
}
