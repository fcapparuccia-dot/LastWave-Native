package com.lastwave.app.data.music

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

/**
 * The country chart of YouTube Music's charts page only exists as a playlist
 * card, and the card titles are localized — these tests pin the choice so a
 * "Live Performances" card can never be served as the country chart.
 */
class ChartCardsParserTest {

    private lateinit var api: InnerTubeMusicApi

    @Before
    fun setUp() {
        api = InnerTubeMusicApi(
            http = mockk(relaxed = true),
            streamExtractor = mockk(relaxed = true),
            innerTubeXExtractor = mockk(relaxed = true),
            ytAuth = mockk(relaxed = true),
            settingsPreferences = mockk(relaxed = true),
            appContext = mockk(relaxed = true),
        )
    }

    private fun card(title: String, browseId: String, pageType: String = "MUSIC_PAGE_TYPE_PLAYLIST") = """
        {
            "musicTwoRowItemRenderer": {
                "title": { "runs": [ { "text": "$title" } ] },
                "navigationEndpoint": {
                    "browseEndpoint": {
                        "browseId": "$browseId",
                        "browseEndpointContextSupportedConfigs": {
                            "browseEndpointContextMusicConfig": { "pageType": "$pageType" }
                        }
                    }
                }
            }
        }
    """.trimIndent()

    private fun page(vararg cards: String) = """
        {
            "contents": {
                "sectionListRenderer": {
                    "contents": [
                        {
                            "musicCarouselShelfRenderer": {
                                "contents": [ ${cards.joinToString(",")} ]
                            }
                        }
                    ]
                }
            }
        }
    """.trimIndent()

    private fun chartOf(vararg cards: String): YouTubePlaylistSummary? =
        api.parseChartPlaylistCards(Json.parseToJsonElement(page(*cards))).bestChartCard()

    @Test
    fun countryChartWinsOverTheLivePerformancesCardThatOpensTheMarket() {
        val picked = chartOf(
            card("Top 100 Live Performances - United Kingdom", "VLPLlive"),
            card("Trending 20 United Kingdom", "VLOLAK5uy_trending"),
            card("Daily Top Music Videos - United Kingdom", "VLPLdaily"),
            card("Top 100 Music Videos United Kingdom", "VLPLchart"),
        )

        assertThat(picked?.id).isEqualTo("VLPLchart")
        assertThat(picked?.title).isEqualTo("Top 100 Music Videos United Kingdom")
    }

    @Test
    fun localizedItalianTitlesStillResolveTheChart() {
        val picked = chartOf(
            card("20 video di tendenza in Italia", "VLOLAK5uy_trending"),
            card("Video musicali più visti del giorno: Italia", "VLPLdaily"),
            card("I 100 video musicali più visti in Italia", "VLPLchart"),
        )

        assertThat(picked?.id).isEqualTo("VLPLchart")
    }

    @Test
    fun livePerformancesIsNeverChosenWhenTheMarketOnlyHasThatHundred() {
        // US/GB markets open with the live chart; the daily one must win instead.
        val picked = chartOf(
            card("Le 100 performance live più popolari - Stati Uniti", "VLPLlive"),
            card("Video musicali più visti del giorno: Stati Uniti", "VLPLdaily"),
        )

        assertThat(picked?.id).isEqualTo("VLPLdaily")
    }

    @Test
    fun artistAndAlbumCardsAreNotCharts() {
        val picked = chartOf(
            card("Top 100 Music Videos Italy", "UCartist", pageType = "MUSIC_PAGE_TYPE_ARTIST"),
            card("Top 100 albums", "MPREalbum", pageType = "MUSIC_PAGE_TYPE_ALBUM"),
        )

        assertThat(picked).isNull()
    }

    @Test
    fun aPageWithoutCardsHasNoChart() {
        assertThat(chartOf()).isNull()
    }
}
