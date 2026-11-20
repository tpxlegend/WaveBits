// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package com.music.spotui.extensions

import app.wavebits.extensions.Catalog
import app.wavebits.extensions.ExtensionPackage
import app.wavebits.extensions.Snapshot
import java.io.File

/**
 * Built-in Lyrics Extension ("WaveBits Lyrics+") bundled with the app and active in
 * YouTube Music Mode and Spotify Mode.
 *
 * Runs inside the standard [app.wavebits.extensions.ExtensionRuntime] sandbox and provides:
 * - Word-by-word karaoke lyrics via LyricsPlus
 * - Line-synced LRC & plain lyrics via LRCLIB
 * - Line-synced LRC fallback via NetEase Cloud Music
 */
object BundledLyricsExtension {
    const val ID = "io.wavebits.lyrics.synced"

    private val MANIFEST_JSON = """
        {
          "id": "io.wavebits.lyrics.synced",
          "name": "WaveBits Lyrics+",
          "version": "1.2.0",
          "versionCode": 12,
          "apiVersion": 1,
          "author": "WaveBits",
          "description": "Multi-source synced & karaoke lyrics extension for Spotify. Resolves word-by-word and line-synced lyrics from LyricsPlus, LRCLIB, and NetEase.",
          "homepage": "https://github.com/kota-zo/myspoti",
          "main": "main.js",
          "category": "lyrics",
          "tags": ["lyrics", "synced", "karaoke"],
          "actions": [
            {
              "name": "lyrics.provide",
              "why": "Provides synced and plain lyrics for Spotify playback"
            },
            {
              "name": "network",
              "hosts": [
                "lrclib.net",
                "lyricsplus.binimum.org",
                "lyricsplus.prjktla.my.id",
                "music.163.com"
              ],
              "why": "Fetches synced lyrics from LRCLIB, LyricsPlus and NetEase"
            },
            {
              "name": "storage",
              "why": "Caches resolved lyrics on device for instant playback"
            },
            {
              "name": "settings.page",
              "why": "Lets you choose preferred lyrics sync style and fallback catalogues"
            }
          ],
          "settings": [
            {
              "key": "prefer_word_sync",
              "type": "toggle",
              "label": "Word-by-word karaoke sync",
              "description": "Prefer word-timed lyrics from LyricsPlus when available",
              "default": true
            },
            {
              "key": "netease_fallback",
              "type": "toggle",
              "label": "NetEase LRC fallback",
              "description": "Search NetEase Cloud Music for line-synced LRC when other sources miss",
              "default": true
            }
          ]
        }
    """.trimIndent()

    private val MAIN_JS = """
        'use strict';

        function decodeHtml(str) {
          if (!str) return '';
          return String(str)
            .replace(/&quot;/g, '"')
            .replace(/&#039;/g, "'")
            .replace(/&apos;/g, "'")
            .replace(/&amp;/g, '&')
            .replace(/&lt;/g, '<')
            .replace(/&gt;/g, '>');
        }

        function cleanTitle(raw) {
          var t = decodeHtml(raw || '');
          t = t.split(' - ')[0];
          t = t.replace(/\s*[\(\[][^\)\]]*(?:from\s+|feat\.?|ft\.?|with\s+|remaster|live|version|edit|deluxe|bonus|ost|soundtrack)[^\)\]]*[\)\]]/gi, '');
          return t.trim();
        }

        function cleanArtist(raw) {
          var a = decodeHtml(raw || '');
          return a.split(',')[0].split('&')[0].split('/')[0].trim();
        }

        function parseLrc(lrcText) {
          if (!lrcText) return [];
          var rows = String(lrcText).split('\n');
          var out = [];
          var tagRe = /\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]/g;
          for (var i = 0; i < rows.length; i++) {
            var line = rows[i];
            tagRe.lastIndex = 0;
            var matches = [];
            var m;
            var lastIdx = 0;
            while ((m = tagRe.exec(line)) !== null) {
              matches.push(m);
              lastIdx = tagRe.lastIndex;
            }
            if (matches.length === 0) continue;
            var text = line.substring(lastIdx).trim();
            if (!text) text = '♪';
            if (text.indexOf(' : ') !== -1 || text.indexOf('：') !== -1) continue;
            for (var j = 0; j < matches.length; j++) {
              var tag = matches[j];
              var min = parseInt(tag[1], 10) || 0;
              var sec = parseInt(tag[2], 10) || 0;
              var frac = tag[3] || '';
              var ms = 0;
              if (frac.length === 1) ms = parseInt(frac, 10) * 100;
              else if (frac.length === 2) ms = parseInt(frac, 10) * 10;
              else if (frac.length >= 3) ms = parseInt(frac.substring(0, 3), 10);
              out.push({ time: min * 60000 + sec * 1000 + ms, text: text });
            }
          }
          out.sort(function (a, b) { return a.time - b.time; });
          return out;
        }

        function tryLyricsPlus(title, artist, durationMs) {
          var preferWords = myspoti.settings.get('prefer_word_sync');
          if (preferWords === false) return null;
          var hosts = ['https://lyricsplus.binimum.org', 'https://lyricsplus.prjktla.my.id'];
          var durSec = durationMs > 0 ? Math.round(durationMs / 1000) : undefined;
          var q = myspoti.query({ title: title, artist: artist, duration: durSec });
          for (var h = 0; h < hosts.length; h++) {
            try {
              var r = myspoti.http.get(hosts[h] + '/v2/lyrics/get?' + q, { timeout: 3500 });
              if (!r.ok || !r.body) continue;
              var data = r.json();
              if (!data || !Array.isArray(data.lyrics) || data.lyrics.length === 0) continue;
              var lines = [];
              for (var i = 0; i < data.lyrics.length; i++) {
                var item = data.lyrics[i];
                if (!item) continue;
                var words = [];
                if (Array.isArray(item.syllabus)) {
                  for (var w = 0; w < item.syllabus.length; w++) {
                    var sw = item.syllabus[w];
                    if (!sw || sw.isBackground) continue;
                    var wt = Number(sw.time) || 0;
                    var wd = Number(sw.duration) || 0;
                    words.push({ time: wt, end: wt + wd, text: String(sw.text || '') });
                  }
                }
                var txt = words.length > 0
                  ? words.map(function (x) { return x.text; }).join('').trim()
                  : String(item.text || '').trim();
                if (txt) {
                  lines.push({
                    time: Number(item.time) || 0,
                    text: txt,
                    words: words.length > 0 ? words : undefined
                  });
                }
              }
              if (lines.length > 0) {
                return { provider: 'WaveBits Lyrics+ · Karaoke', synced: true, lines: lines };
              }
            } catch (e) {}
          }
          return null;
        }

        function tryLrclib(title, artist, album, durationMs) {
          var durSec = durationMs > 0 ? Math.round(durationMs / 1000) : undefined;
          try {
            var q = myspoti.query({
              track_name: title,
              artist_name: artist,
              album_name: album || undefined,
              duration: durSec
            });
            var r = myspoti.http.get('https://lrclib.net/api/get?' + q, { timeout: 3500 });
            if (r.ok && r.body) {
              var o = r.json();
              var parsed = extractLrclibObject(o);
              if (parsed) return parsed;
            }
          } catch (e) {}

          try {
            var sq = myspoti.query({ q: title + ' ' + artist });
            var sr = myspoti.http.get('https://lrclib.net/api/search?' + sq, { timeout: 3500 });
            if (sr.ok && sr.body) {
              var arr = sr.json();
              if (Array.isArray(arr) && arr.length > 0) {
                var best = null;
                for (var i = 0; i < arr.length; i++) {
                  var cand = arr[i];
                  if (cand && cand.syncedLyrics) { best = cand; break; }
                  if (cand && cand.plainLyrics && !best) best = cand;
                }
                if (best) {
                  var res = extractLrclibObject(best);
                  if (res) return res;
                }
              }
            }
          } catch (e) {}
          return null;
        }

        function extractLrclibObject(o) {
          if (!o) return null;
          if (o.syncedLyrics) {
            var lines = parseLrc(o.syncedLyrics);
            if (lines.length > 0) {
              return { provider: 'WaveBits Lyrics+ · LRCLIB', synced: true, lines: lines };
            }
          }
          if (o.plainLyrics) {
            var plain = String(o.plainLyrics).trim();
            if (plain) {
              return { provider: 'WaveBits Lyrics+ · LRCLIB', plain: plain };
            }
          }
          return null;
        }

        function tryNetEase(title, artist) {
          if (myspoti.settings.get('netease_fallback') === false) return null;
          try {
            var q = myspoti.query({ s: title + ' ' + artist, type: 1, limit: 5 });
            var sr = myspoti.http.get('https://music.163.com/api/search/get?' + q, { timeout: 3500 });
            if (!sr.ok || !sr.body) return null;
            var data = sr.json();
            var songs = data && data.result && data.result.songs;
            if (!Array.isArray(songs) || songs.length === 0) return null;
            var songId = songs[0].id;
            if (!songId) return null;
            var lr = myspoti.http.get('https://music.163.com/api/song/lyric?id=' + songId + '&lv=1&kv=1&tv=-1', { timeout: 3500 });
            if (!lr.ok || !lr.body) return null;
            var lrcObj = lr.json();
            var lrc = lrcObj && lrcObj.lrc && lrcObj.lrc.lyric;
            var lines = parseLrc(lrc);
            if (lines.length > 0) {
              return { provider: 'WaveBits Lyrics+ · NetEase', synced: true, lines: lines };
            }
          } catch (e) {}
          return null;
        }

        myspoti.provide('status', function () {
          return { live: true, detail: 'LyricsPlus · LRCLIB · NetEase ready' };
        });

        myspoti.provide('lyrics', function (track) {
          if (!track || !track.title) return null;
          var title = cleanTitle(track.title);
          var artist = cleanArtist(track.artist);
          var cacheKey = 'v1:' + (track.id || (title.toLowerCase() + '|' + artist.toLowerCase()));
          var cached = myspoti.storage.get(cacheKey);
          if (cached && (cached.lines || cached.plain)) return cached;

          var res =
            tryLyricsPlus(title, artist, track.durationMs || 0) ||
            tryLrclib(title, artist, decodeHtml(track.album || ''), track.durationMs || 0) ||
            tryNetEase(title, artist);

          if (res) {
            try { myspoti.storage.set(cacheKey, res); } catch (e) {}
          }
          return res;
        });
    """.trimIndent()

    private val cachedZip: Pair<ByteArray, ExtensionPackage> by lazy {
        val bytes = ExtensionPackage.packFiles(
            mapOf(
                "manifest.json" to MANIFEST_JSON.toByteArray(Charsets.UTF_8),
                "main.js" to MAIN_JS.toByteArray(Charsets.UTF_8),
            )
        )
        bytes to ExtensionPackage.read(bytes)
    }

    fun packagePair(): Pair<ByteArray, ExtensionPackage> = cachedZip

    fun catalogEntry(): Catalog.Entry {
        val (zip, pkg) = cachedZip
        val m = pkg.manifest
        return Catalog.Entry(
            id = m.id,
            name = m.name,
            version = m.version,
            versionCode = m.versionCode,
            apiVersion = m.apiVersion,
            author = m.author,
            description = m.description,
            actions = m.actions,
            url = "https://raw.githubusercontent.com/kota-zo/myspoti-extensions/main/io.wavebits.lyrics.synced.zip",
            sha256 = ExtensionPackage.sha256(zip),
            homepage = m.homepage,
            category = m.category,
            tags = m.tags,
        )
    }

    /**
     * Installs or upgrades the bundled WaveBits Lyrics+ extension on disk and in [Extensions.registry]
     * so it is ready on cold start and in Guest Mode.
     */
    fun seedIfNeeded(dir: File, current: Registry, forceEnable: Boolean = true): Registry {
        val (zip, pkg) = runCatching { packagePair() }.getOrNull() ?: return current
        val m = pkg.manifest
        val expectedGrants = Grants.keys(m)
        val existing = current.find(ID)
        if (existing != null &&
            existing.sha256 == pkg.sha256 &&
            existing.disabledReason == null &&
            existing.granted.containsAll(expectedGrants) &&
            (!forceEnable || existing.enabled)
        ) {
            val pkgFile = File(dir, "$ID/package.zip")
            if (pkgFile.isFile) {
                Extensions.packages[ID] = pkg
                return current
            }
        }
        val folder = File(dir, ID).apply { mkdirs() }
        Extensions.writeBytesAtomically(File(folder, "package.zip"), zip)
        Extensions.packages[ID] = pkg
        val shouldEnable = when {
            forceEnable -> true
            existing == null -> true
            existing.disabledReason != null -> true
            else -> existing.enabled
        }
        val installed = InstalledExtension(
            id = m.id,
            name = m.name,
            version = m.version,
            versionCode = m.versionCode,
            author = m.author,
            sha256 = pkg.sha256,
            granted = expectedGrants,
            enabled = shouldEnable,
            disabledReason = null,
            installedAt = existing?.installedAt ?: System.currentTimeMillis(),
            source = "built-in",
            accepted = Snapshot.of(m, null),
        )
        return current
            .copy(noticeAccepted = current.noticeAccepted + m.id)
            .put(installed)
    }
}
