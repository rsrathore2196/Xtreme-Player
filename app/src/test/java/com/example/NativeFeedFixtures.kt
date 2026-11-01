package com.example

import org.json.JSONArray
import org.json.JSONObject

internal object NativeFeedFixtures {
    fun id(number: Int) = number.toString().padStart(11, '0')
    fun text(value: String) = JSONObject().put("runs", JSONArray().put(JSONObject().put("text", value)))
    fun song(number: Int, title: String = "Song $number"): JSONObject {
        fun column(value: JSONObject) = JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", value))
        val artist = text("Singer $number")
        artist.getJSONArray("runs").getJSONObject(0).put("navigationEndpoint", JSONObject().put("browseEndpoint",
            JSONObject().put("browseId", "UC-singer-$number")))
        return JSONObject().put("musicResponsiveListItemRenderer", JSONObject()
            .put("playlistItemData", JSONObject().put("videoId", id(number)))
            .put("flexColumns", JSONArray().put(column(text(title))).put(column(artist)))
            .put("thumbnail", JSONObject().put("musicThumbnailRenderer", JSONObject().put("thumbnail", JSONObject()
                .put("thumbnails", JSONArray().put(JSONObject().put("url", "https://art.example/$number.jpg").put("width", 600)))))))
    }
    fun album(number: Int): JSONObject = JSONObject().put("musicTwoRowItemRenderer", JSONObject()
        .put("title", text("Album $number")).put("navigationEndpoint", JSONObject().put("browseEndpoint",
            JSONObject().put("browseId", "MPRE$number"))))
    fun playlist(number: Int): JSONObject = JSONObject().put("musicTwoRowItemRenderer", JSONObject()
        .put("title", text("Playlist $number")).put("subtitle", text("YouTube Music"))
        .put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", "VLplaylist-$number")
            .put("params", "playlist-params-$number").put("browseEndpointContextSupportedConfigs", JSONObject()
                .put("browseEndpointContextMusicConfig", JSONObject().put("pageType", "MUSIC_PAGE_TYPE_PLAYLIST"))))))
    fun collectionShelf(title: String, vararg entries: JSONObject): JSONObject = shelf(title).apply {
        getJSONObject("musicCarouselShelfRenderer").put("contents", JSONArray(entries.toList()))
    }
    fun shelf(title: String, vararg numbers: Int): JSONObject = JSONObject().put("musicCarouselShelfRenderer", JSONObject()
        .put("header", JSONObject().put("musicCarouselShelfBasicHeaderRenderer", JSONObject().put("title", text(title))))
        .put("contents", JSONArray(numbers.map { song(it) })))
    fun home(vararg shelves: JSONObject, continuation: String? = null): String {
        val list = JSONObject().put("contents", JSONArray(shelves.toList()))
        if (continuation != null) list.put("continuations", JSONArray().put(JSONObject().put("nextContinuationData",
            JSONObject().put("continuation", continuation))))
        return JSONObject().put("contents", JSONObject().put("singleColumnBrowseResultsRenderer", JSONObject().put("tabs",
            JSONArray().put(JSONObject().put("tabRenderer", JSONObject().put("content", JSONObject().put("sectionListRenderer", list))))))).toString()
    }
    fun radio(vararg numbers: Int, continuation: String? = null): String {
        val panel = JSONObject().put("playlistId", "RDAMVM${id(1)}").put("contents", JSONArray(numbers.map { song(it) }))
        if (continuation != null) panel.put("continuations", JSONArray().put(JSONObject().put("nextContinuationData",
            JSONObject().put("continuation", continuation))))
        return JSONObject().put("continuationContents", JSONObject().put("playlistPanelContinuation", panel)).toString()
    }
}
