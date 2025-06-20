/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.client.parser

import arrow.core.Either
import arrow.core.getOrElse
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.client.data.GalleryComment
import com.hippo.ehviewer.client.data.GalleryCommentList
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryTag
import com.hippo.ehviewer.client.data.GalleryTagGroup
import com.hippo.ehviewer.client.data.PowerStatus
import com.hippo.ehviewer.client.data.TagNamespace
import com.hippo.ehviewer.client.data.V1GalleryPreview
import com.hippo.ehviewer.client.data.V2GalleryPreview
import com.hippo.ehviewer.client.data.VoteStatus
import com.hippo.ehviewer.client.exception.EhException
import com.hippo.ehviewer.client.exception.ParseException
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.util.toEpochMillis
import com.hippo.ehviewer.util.toFloatOrDefault
import com.hippo.ehviewer.util.toIntOrDefault
import com.hippo.ehviewer.util.trimAnd
import com.hippo.ehviewer.util.unescapeXml
import eu.kanade.tachiyomi.util.system.logcat
import java.nio.ByteBuffer
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.NodeTraversor

object GalleryDetailParser {
    private val PATTERN_ERROR = Regex("<div class=\"d\">[^<]*<p>([^<]+)</p>")
    private val PATTERN_DETAIL =
        Regex("var gid = (\\d+);\\s+var token = \"([a-f0-9]+)\";\\s+var apiuid = ([\\-\\d]+);\\s+var apikey = \"([a-f0-9]+)\";")
    private val PATTERN_TORRENT =
        Regex("<a[^<>]*onclick=\"return popUp\\('([^']+)'[^)]+\\)\">Torrent Download \\((\\d+)\\)</a>")
    private val PATTERN_ARCHIVE =
        Regex("<a[^<>]*onclick=\"return popUp\\('([^']+)'[^)]+\\)\">Archive Download</a>")
    private val PATTERN_COVER = Regex("(\\d+)\\D+(\\d+)[^(]+\\(([^)]+)")
    private val PATTERN_PAGES =
        Regex("<tr><td[^<>]*>Length:</td><td[^<>]*>([\\d,]+) pages</td></tr>")
    private val PATTERN_NEWER_DATE = Regex(", added ([^<]+)<br />")
    private val PATTERN_FAVORITE_SLOT =
        Regex("/fav\\.png\\); background-position:0px -(\\d+)px")
    private val EMPTY_GALLERY_COMMENT_LIST = GalleryCommentList(emptyList(), false)
    private val PreviewRegex =
        Regex("<a href=\"https[^s#\"]+(?:s/([0-9a-f]{10})/\\d+-|#page)(\\d+)\">(?:<div>)?<div title=\"(?:[^\"]+\"){2}\\D+(\\d+)\\D+(\\d+)[^(]+\\(([^)]+)\\)(?: -(\\d+))?")

    // dd MMMM yyyy, HH:mm
    private val WEB_COMMENT_DATE_FORMAT = LocalDateTime.Format {
        dayOfMonth()
        char(' ')
        monthName(MonthNames.ENGLISH_FULL)
        char(' ')
        year()
        chars(", ")
        hour()
        char(':')
        minute()
    }

    fun parse(body: String): GalleryDetail {
        PATTERN_ERROR.find(body)?.run { throw EhException(groupValues[1].replace("<br>", "\n")) }
        val document = Jsoup.parse(body)
        val galleryDetail = GalleryDetail(
            tagGroups = parseTagGroups(document),
            comments = parseComments(document),
            previewList = parsePreviewList(body),
        )
        parseDetail(galleryDetail, document, body)
        return galleryDetail
    }

    private fun parseDetail(gd: GalleryDetail, d: Document, body: String) {
        PATTERN_DETAIL.find(body)?.apply {
            gd.gid = groupValues[1].toLongOrNull() ?: -1L
            gd.token = groupValues[2]
            gd.apiUid = groupValues[3].toLongOrNull() ?: -1L
            gd.apiKey = groupValues[4]
        } ?: throw ParseException("Can't parse gallery detail")
        if (gd.gid == -1L) {
            throw ParseException("Can't parse gallery detail")
        }
        PATTERN_TORRENT.find(body)?.run {
            gd.torrentUrl = groupValues[1].trim().unescapeXml()
            gd.torrentCount = groupValues[2].toIntOrNull() ?: 0
        }
        PATTERN_ARCHIVE.find(body)?.run {
            gd.archiveUrl = groupValues[1].trim().unescapeXml()
        }
        Either.catch {
            val gm = d.getElementsByClass("gm")[0]
            // Thumb url
            gm.getElementById("gd1")?.child(0)?.attr("style")?.trim()?.let {
                gd.thumbKey = getThumbKey(PATTERN_COVER.find(it)!!.groupValues[3])
            }

            gd.title = gm.getElementById("gn")?.text()?.trim()
            gd.titleJpn = gm.getElementById("gj")?.text()?.trim()

            // Category
            gd.category = Either.catch {
                val gdc = gm.getElementById("gdc")!!
                var ce = gdc.getElementsByClass("cn").first()
                if (ce == null) {
                    ce = gdc.getElementsByClass("cs").first()
                }
                EhUtils.getCategory(ce!!.text())
            }.getOrElse {
                EhUtils.UNKNOWN
            }

            // Uploader
            val gdn = gm.getElementById("gdn")
            if (null != gdn) {
                gd.disowned = gdn.attr("style").contains("opacity:0.5")
                gd.uploader = gdn.text().trim()
            } else {
                gd.uploader = ""
            }
            val gdd = gm.getElementById("gdd")
            gd.posted = ""
            gd.parent = ""
            gd.visible = ""
            gd.size = ""
            gd.pages = 0
            gd.favoriteCount = 0
            val es = gdd!!.child(0).child(0).children()
            es.forEach {
                parseDetailInfo(gd, it)
            }

            // Rating count
            val ratingCount = gm.getElementById("rating_count")
            if (null != ratingCount) {
                gd.ratingCount = ratingCount.text().trim().toIntOrDefault(0)
            } else {
                gd.ratingCount = 0
            }

            // Rating
            val ratingLabel = gm.getElementById("rating_label")
            if (null != ratingLabel) {
                val ratingStr = ratingLabel.text().trim()
                if ("Not Yet Rated" == ratingStr) {
                    gd.rating = -1.0f
                } else {
                    val index = ratingStr.indexOf(' ')
                    if (index == -1 || index >= ratingStr.length) {
                        gd.rating = 0f
                    } else {
                        gd.rating = ratingStr.substring(index + 1).toFloatOrDefault(0f)
                    }
                }
            } else {
                gd.rating = -1.0f
            }

            // isFavorited
            val gdf = gm.getElementById("gdf")
            if (gdf != null) {
                val favoriteName = gdf.text().trim()
                if (favoriteName != "Add to Favorites") {
                    gd.favoriteName = gdf.text().trim()
                    PATTERN_FAVORITE_SLOT.find(body)?.run {
                        gd.favoriteSlot = ((groupValues[1].toIntOrNull() ?: 2) - 2) / 19
                    }
                }
            }
        }.getOrElse {
            throw ParseException("Can't parse gallery detail")
        }

        // newer version
        d.getElementById("gnd")?.run {
            val dates = PATTERN_NEWER_DATE.findAll(body).map { it.groupValues[1] }.toList()
            gd.newerVersions = select("a").mapIndexedNotNull { index, element ->
                val result = GalleryDetailUrlParser.parse(element.attr("href"))
                result?.run {
                    BaseGalleryInfo(gid, token, element.text().trim(), posted = dates[index])
                }
            }
        }
    }

    private fun parseDetailInfo(gd: GalleryDetail, e: Element) {
        val es = e.children()
        if (es.size < 2) {
            return
        }
        val key = es[0].text().trim()
        val value = es[1].ownText().trim()
        if (key.startsWith("Posted")) {
            gd.posted = value
        } else if (key.startsWith("Parent")) {
            val a = es[1].children().first()
            if (a != null) {
                gd.parent = a.attr("href")
            }
        } else if (key.startsWith("Visible")) {
            gd.visible = value
        } else if (key.startsWith("Language")) {
            gd.language = value
        } else if (key.startsWith("File Size")) {
            gd.size = value
        } else if (key.startsWith("Length")) {
            val index = value.indexOf(' ')
            if (index >= 0) {
                gd.pages = value.substring(0, index).toIntOrDefault(1)
            } else {
                gd.pages = 1
            }
        } else if (key.startsWith("Favorited")) {
            when (value) {
                "Never" -> gd.favoriteCount = 0
                "Once" -> gd.favoriteCount = 1
                else -> {
                    val index = value.indexOf(' ')
                    if (index == -1) {
                        gd.favoriteCount = 0
                    } else {
                        gd.favoriteCount = value.substring(0, index).toIntOrDefault(0)
                    }
                }
            }
        }
    }

    private fun parseSingleTagGroup(element: Element): GalleryTagGroup? = Either.catch {
        val nameSpace = with(element.child(0).text()) {
            // Remove last ':'
            TagNamespace.from(substring(0, length - 1))
        }
        checkNotNull(nameSpace) { "$nameSpace is not a valid tag namespace!" }
        val tags = element.child(1).children().map { e ->
            GalleryTag(
                // Sometimes parody tag is followed with '|' and english translate, just remove them
                text = e.text().substringBefore('|').trim(),
                power = when {
                    e.hasClass("gtw") -> PowerStatus.Weak
                    e.hasClass("gtl") -> PowerStatus.Active
                    else -> PowerStatus.Solid
                },
                vote = when {
                    e.child(0).hasClass("tup") -> VoteStatus.Up
                    e.child(0).hasClass("tdn") -> VoteStatus.Down
                    else -> VoteStatus.None
                },
            )
        }
        check(tags.isNotEmpty()) { "TagGroup is empty!" }
        GalleryTagGroup(nameSpace, tags)
    }.getOrElse {
        logcat(it)
        null
    }

    /**
     * Parse tag groups with html parser
     */
    fun parseTagGroups(document: Document): List<GalleryTagGroup> = Either.catch {
        val taglist = document.getElementById("taglist")!!
        taglist.child(0).child(0).children().mapNotNull(::parseSingleTagGroup)
    }.getOrElse {
        logcat(it)
        emptyList()
    }

    private fun parseComment(element: Element): GalleryComment? = Either.catch {
        // Id
        val a = element.previousElementSibling()
        val name = a!!.attr("name")
        val id = name trimAnd { substring(1).toInt().toLong() }
        // Editable, vote up and vote down
        val c4 = element.getElementsByClass("c4").first()
        var voteUpAble = false
        var voteUpEd = false
        var voteDownAble = false
        var voteDownEd = false
        var editable = false
        var uploader = false
        if (null != c4) {
            if ("Uploader Comment" == c4.text()) {
                uploader = true
            }
            for (e in c4.children()) {
                when (e.text()) {
                    "Vote+" -> {
                        voteUpAble = true
                        voteUpEd = e.attr("style").trim().isNotEmpty()
                    }

                    "Vote-" -> {
                        voteDownAble = true
                        voteDownEd = e.attr("style").trim().isNotEmpty()
                    }

                    "Edit" -> editable = true
                }
            }
        }
        // Vote state
        val c7 = element.getElementsByClass("c7").first()
        val voteState = c7?.text()?.trim()
        // Score
        val c5 = element.getElementsByClass("c5").first()
        val score = c5?.children()?.first()?.text()?.trim()?.toIntOrDefault(0) ?: 0
        // time
        val c3 = element.getElementsByClass("c3").first()
        val temp = c3!!.ownText()
        val (timeStr, user) = if (temp.endsWith(':')) {
            temp.substring("Posted on ".length, temp.length - " by:".length) to c3.child(0).text()
        } else {
            temp.substring("Posted on ".length) to null
        }
        val time = WEB_COMMENT_DATE_FORMAT.parse(timeStr).toEpochMillis()
        // comment
        val c6 = element.getElementsByClass("c6").first()
        // fix underline support
        for (e in c6!!.children()) {
            if ("span" == e.tagName() && "text-decoration:underline;" == e.attr("style")) {
                e.tagName("u")
            }
        }
        val commentHtml = c6.html()
        // last edited
        val c8 = element.getElementsByClass("c8").first()
        val lastEdited = c8?.children()?.first()?.run { WEB_COMMENT_DATE_FORMAT.parse(text()).toEpochMillis() } ?: 0
        GalleryComment(
            id, score, editable, voteUpAble, voteUpEd, voteDownAble,
            voteDownEd, uploader, voteState, time, user, commentHtml, lastEdited,
        )
    }.getOrElse {
        logcat(it)
        null
    }

    /**
     * Parse comments with html parser
     */
    fun parseComments(document: Document): GalleryCommentList = Either.catch {
        // Disable pretty print to get comments in raw html
        document.outputSettings().prettyPrint(false)
        val cdiv = document.getElementById("cdiv")!!
        val c1s = cdiv.getElementsByClass("c1")
        val list = c1s.mapNotNull { parseComment(it) }
        val chd = cdiv.getElementById("chd")
        var hasMore = false
        NodeTraversor.traverse(
            { node, depth ->
                if (node is Element && node.text() == "click to show all") {
                    hasMore = true
                }
            },
            chd!!,
        )
        GalleryCommentList(list, hasMore)
    }.getOrElse {
        logcat(it)
        EMPTY_GALLERY_COMMENT_LIST
    }

    /**
     * Parse pages with regular expressions
     */
    fun parsePages(body: String): Int = PATTERN_PAGES.find(body)?.groupValues?.get(1)?.toIntOrNull()
        ?: throw ParseException("Parse pages error")

    fun parsePreviewList(body: String) = PreviewRegex.findAll(body).map {
        val pToken = it.groupValues[1]
        val position = it.groupValues[2].toInt() - 1
        val url = it.groupValues[5]
        val offset = it.groupValues[6]
        if (offset.isEmpty()) {
            V1GalleryPreview(url, position, pToken)
        } else {
            val width = it.groupValues[3].toInt()
            val height = it.groupValues[4].toInt()
            V2GalleryPreview(url, position, pToken, offset.toInt(), width, height)
        }
    }.toList().apply {
        if (isEmpty()) throw ParseException("Parse preview list error")
    }

    fun parseRust(body: ByteBuffer) = Either.catch {
        unmarshalParsingAs<Result>(body, ::parseGalleryDetail)
    }.getOrElse {
        throw ParseException("Failed to parse gallery detail", it)
    }

    @Serializable
    @CborArray
    class Result(val detail: GalleryDetail, val event: String?)

    private external fun parseGalleryDetail(body: ByteBuffer, size: Int = body.limit()): Int
}
