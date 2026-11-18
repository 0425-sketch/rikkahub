package me.rerere.rikkahub.utils

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class PersonalReleaseFeedTest {
    private val releasesUrl = "https://api.github.com/repos/0425-sketch/rikkahub/releases?per_page=100&page="
    private val digest = "a".repeat(64)
    private val shortSha = "4ac14e80bb24"
    private val source = shortSha + "0".repeat(28)
    private val signer = "882c1cc87ccf36c2ac5eb178b8d8ef13ff74a3ac8b759b41aed6e48bddb4e1fd"

    private inner class Delivery(val code: Int) {
        val tag = "custom-3.4.0-personal-$shortSha-$code-1"
        val download = "https://github.com/0425-sketch/rikkahub/releases/download/$tag"
        val name = "rikkahub-3.4.0-personal.1-$shortSha-arm64.apk"
        val proofUrl = "$download/build-provenance.json"
        val listing = """{
            "tag_name":"$tag", "target_commitish":"$source", "draft":false, "prerelease":true,
            "published_at":"2026-11-17T11:47:57Z", "body":"Release notes", "assets":[
              {"name":"build-provenance.json","state":"uploaded","size":827,"browser_download_url":"$proofUrl"},
              {"name":"$name","state":"uploaded","size":512,"browser_download_url":"$download/$name","digest":"sha256:$digest"}
            ]}"""
        val proof = """{
            "tag":"$tag","source_commit":"$source","application_id":"me.rerere.rikkahub.personal",
            "architectures":["arm64-v8a"],"certificate_sha256":"$signer",
            "version_code":$code,"version_name":"3.4.0-personal.1","apk_size":512,"apk_sha256":"$digest"
        }"""
    }

    private suspend fun checkDelivery(
        release: Delivery,
        installed: Int = 201,
        listing: String = release.listing,
        proof: String = release.proof,
    ) = PersonalReleaseFeed.latest(installed) { url ->
        when (url) {
            "${releasesUrl}1" -> "[$listing]"
            release.proofUrl -> proof
            else -> error("Unexpected request: $url")
        }
    }

    @Test
    fun `includes personal pre-releases and selects highest version code across all pages`() = runTest {
        val first = Delivery(208)
        val equalToInstalled = Delivery(201)
        val winner = Delivery(209)
        val unrelated = """{"tag_name":"3.5.0","draft":false}"""
        val firstPage = List(99) { unrelated } + first.listing
        val result = PersonalReleaseFeed.latest(201) { url ->
            when (url) {
                "${releasesUrl}1" -> firstPage.joinToString(prefix = "[", postfix = "]")
                "${releasesUrl}2" -> "[${equalToInstalled.listing},${winner.listing}]"
                first.proofUrl -> first.proof
                equalToInstalled.proofUrl -> equalToInstalled.proof
                winner.proofUrl -> winner.proof
                else -> error("Unexpected request: $url")
            }
        }
        assertEquals(209, result?.versionCode)
        assertEquals("3.4.0-personal.1", result?.versionName)
        assertEquals("${winner.download}/${winner.name}", result?.apkUrl)
        assertEquals("Release notes", result?.changelog)
    }

    @Test
    fun `published code hints skip obsolete proof requests but cannot promote a release`() = runTest {
        val installed = Delivery(201)
        val winner = Delivery(210)
        val older = Delivery(209)
        val exaggerated = Delivery(202)
        fun Delivery.withHint(hint: Int) = listing.replace(
            "Release notes", "Release notes\\n\\n<!-- rikkahub-personal-version-code: $hint -->",
        )
        val requested = mutableListOf<String>()
        val rows = listOf(installed.withHint(201), winner.withHint(210), older.withHint(209), exaggerated.withHint(999))
        val result = PersonalReleaseFeed.latest(201) { url ->
            requested += url
            when (url) {
                "${releasesUrl}1" -> rows.joinToString(prefix = "[", postfix = "]")
                winner.proofUrl -> winner.proof
                exaggerated.proofUrl -> exaggerated.proof
                else -> error("Unexpected proof request")
            }
        }
        assertEquals(210, result?.versionCode)
        assertEquals("Release notes", result?.changelog)
        assertEquals(listOf("${releasesUrl}1", winner.proofUrl, exaggerated.proofUrl), requested)
    }

    @Test
    fun `equal and older codes produce no update even when the version name looks newer`() = runTest {
        val release = Delivery(201)
        assertNull(checkDelivery(release))
        assertNull(checkDelivery(release, installed = 202))
    }

    @Test
    fun `drafts other channels and foreign proof URLs are skipped without fetching them`() = runTest {
        val release = Delivery(202)
        val untrusted = listOf(
            release.listing.replace("\"draft\":false", "\"draft\":true"),
            release.listing.replace(release.tag, "custom-3.4.0-test"),
            release.listing.replace(release.proofUrl, "https://example.org/proof.json"),
        )
        val requests = mutableListOf<String>()
        val result = PersonalReleaseFeed.latest(201) { url ->
            requests += url
            untrusted.joinToString(prefix = "[", postfix = "]")
        }
        assertNull(result)
        assertEquals(listOf("${releasesUrl}1"), requests)
    }

    @Test
    fun `proof must identify this signed package source and exact release asset`() = runTest {
        val r = Delivery(202)
        for (badProof in listOf(
            "{}", r.proof.replace(".personal\"", ".debug\""), r.proof.replace(signer, digest),
            r.proof.replace(source, "f".repeat(40)), r.proof.replace(r.tag, "another-tag"),
            r.proof.replace("arm64-v8a", "x86_64"), r.proof.replace("3.4.0-personal.1", "3.5.0-personal.1"),
            r.proof.replace("512", "513"), r.proof.replace(digest, "b".repeat(64)),
        )) assertNull(checkDelivery(r, proof = badProof))
        val apkUrl = "${r.download}/${r.name}"
        assertNull(checkDelivery(r, listing = r.listing.replace(apkUrl, "https://example.org/${r.name}")))
        assertNull(checkDelivery(r, listing = r.listing.replace(r.name, "official-arm64.apk")))
    }

    @Test
    fun `network errors can be retried by the caller`() {
        assertThrows(IOException::class.java) {
            runTest {
                val r = Delivery(202)
                PersonalReleaseFeed.latest(201) { if (it == "${releasesUrl}1") "[${r.listing}]" else throw IOException() }
            }
        }
    }
}
