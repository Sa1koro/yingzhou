package tvbox.engine.core.model

import kotlinx.serialization.Serializable

// Normalised video data (protocol v1). Clients never see TVBox's "$$$" / "#" / "$" encodings.

@Serializable
data class VodCard(
    val site: String,
    val id: String,
    val name: String,
    val pic: String = "",
    val remarks: String = "",
    /**
     * The site's `vod_tag`, as written. `folder` (and TVBox's `cover`) marks a folder: opening it lists the
     * category whose id is the card's [id] instead of a detail (platform → area → room on live sites).
     */
    val tag: String = "",
) {
    val isFolder: Boolean get() = tag == "folder" || tag == "cover"
}

@Serializable
data class VodClass(
    val id: String,
    val name: String,
)

@Serializable
data class FilterValue(
    val name: String,
    val value: String,
)

@Serializable
data class VodFilter(
    val key: String,
    val name: String,
    val values: List<FilterValue>,
)

@Serializable
data class HomeResult(
    val classes: List<VodClass> = emptyList(),
    /** Keyed by class id. */
    val filters: Map<String, List<VodFilter>> = emptyMap(),
    val list: List<VodCard> = emptyList(),
    val warning: SpiderDiagnostic? = null,
)

@Serializable
data class VodPage(
    val page: Int = 1,
    /** 0 when unknown. */
    val pageCount: Int = 0,
    val total: Int = 0,
    val list: List<VodCard> = emptyList(),
)

@Serializable
data class Episode(
    /** Opaque to clients; pass back to /play/resolve unchanged. */
    val id: String,
    val name: String,
)

@Serializable
data class PlayLine(
    val name: String,
    val episodes: List<Episode>,
)

@Serializable
data class VodDetail(
    val site: String,
    val id: String,
    val name: String,
    val pic: String = "",
    val year: String = "",
    val area: String = "",
    val type: String = "",
    val actor: String = "",
    val director: String = "",
    val content: String = "",
    val remarks: String = "",
    val lines: List<PlayLine> = emptyList(),
)

@Serializable
data class PlayRequest(
    val site: String,
    val line: String,
    val episodeId: String,
    /**
     * The player fetches the video from the source itself (a client far from the engine, e.g. over Tailscale): an
     * HLS film still goes through the engine's playlist endpoint (ads are cut there), but its segments are not
     * served from the stream cache. [PlayInfo.relay] is then the address through the cache, to fall back to.
     */
    val direct: Boolean = false,
    val offerDriveSignIn: Boolean = false,
)

@Serializable
data class DriveSignInPrompt(val handle: String, val provider: String, val qrUrl: String, val image: String? = null)

@Serializable
data class DriveSignInPoll(val site: String, val handle: String)

@Serializable
data class DriveSignInStatus(val status: String)

@Serializable
data class SniffRequest(
    val pageUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 15_000,
    /** JavaScript to run once the page has loaded (the config's click scripts for this page). */
    val scripts: List<String> = emptyList(),
)

@Serializable
data class PlayInfo(
    /** "direct": play [url]; "sniff": load [sniff] in a web view and play the first media request. */
    val kind: String,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val sniff: SniffRequest? = null,
    /** Which parser produced the url, for display ("" when none was needed). */
    val parsedBy: String = "",
    val driveSignIn: DriveSignInPrompt? = null,
    /**
     * Not empty when the engine's stream cache serves [url] (or [relay], when that is set): what was played or read
     * ahead stays on the engine's disk, and `/api/v1/stream-cache/coverage?key=` with this value says which parts
     * are there.
     */
    val cache: String = "",
    /**
     * Only for a [PlayRequest.direct] request whose [url] does not go through the stream cache: the same video
     * through the cache, for a player whose direct fetching failed (a source that only accepts the address that
     * resolved it, `hosts` rules of the config that only the engine applies). Empty when there is no such address.
     */
    val relay: String = "",
    /** External subtitles the site named, each served by the engine as UTF-8 SRT / ASS / WebVTT. */
    val subtitles: List<SubtitleTrack> = emptyList(),
    /**
     * Where the site's danmaku for this episode come from ("" when it named none). For display only: clients get the
     * danmaku themselves from `/api/v1/danmaku`, which also asks the configured danmaku services.
     */
    val danmaku: String = "",
    /** Not empty when [url] is a finished download of the episode on the engine's disk (its id): it plays without the network. */
    val download: String = "",
)

/** An external subtitle of a [PlayInfo]. */
@Serializable
data class SubtitleTrack(
    val name: String,
    /** Language code or name as the site gave it ("" when it did not). */
    val lang: String = "",
    /** What the engine serves at [url]: srt | ass | ssa | vtt. */
    val format: String = "",
    /** The engine's `/subtitle?…` address (signed for LAN clients), not the site's. */
    val url: String,
)

/** Aggregated search events (SSE). */
@Serializable
data class SearchSiteEvent(
    val site: String,
    /** started | done | error */
    val status: String,
    val count: Int = 0,
    val ms: Long = 0,
    val code: String? = null,
    val message: String? = null,
    val diagnostic: SpiderDiagnostic? = null,
)

@Serializable
data class SearchResultsEvent(
    val site: String,
    val items: List<VodCard>,
)

@Serializable
data class SearchEndEvent(
    val sites: Int,
    val ms: Long,
)
