// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.dropbox

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * M17c (PR 33 review): the exact response shapes of the Dropbox API v2 (files.stone). Endpoints whose result type is
 * the concrete `FileMetadata` (upload, upload_session/finish, the download's `Dropbox-API-Result`) send it without
 * `.tag`; endpoints returning the polymorphic `Metadata` (get_metadata, list_folder entries) tag it.
 */
class DropboxJsonTest {
    /** `FileMetadata` as `/2/files/upload` documents it: no `.tag`. */
    private val uploadResult = """
        {"name": "Prime_Numbers.txt", "id": "id:a4ayc_80_OEAAAAAAAAAXw",
         "client_modified": "2015-05-12T15:50:38Z", "server_modified": "2015-05-12T15:50:38Z",
         "rev": "a1c10ce0dd78", "size": 7212, "path_lower": "/homework/math/prime_numbers.txt",
         "path_display": "/Homework/math/Prime_Numbers.txt",
         "sharing_info": {"read_only": true, "parent_shared_folder_id": "84528192421",
                          "modified_by": "dbid:AAH4f99T0taONIb-OurWxbNQ6ywGRopQngc"},
         "is_downloadable": true,
         "property_groups": [{"template_id": "ptid:1a5n2i6d3OYEAAAAAAAAAYa",
                              "fields": [{"name": "Security Policy", "value": "Confidential"}]}],
         "has_explicit_shared_members": false,
         "content_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
         "file_lock_info": {"is_lockholder": true, "lockholder_name": "Imaginary User",
                            "created": "2015-05-12T15:50:38Z"}}
    """.trimIndent()

    /** `Metadata` as `/2/files/get_metadata` documents it: the same fields plus `.tag`. */
    private val getMetadataResult = uploadResult.replaceFirst("{", """{".tag": "file", """)

    @Test
    fun `a concrete FileMetadata without a tag is read as a file`() {
        val e = DropboxJson.fileEntry(DropboxJson.parse(uploadResult.toByteArray()))
        e shouldNotBe null
        e!!.isFolder shouldBe false
        e.name shouldBe "Prime_Numbers.txt"
        e.pathLower shouldBe "/homework/math/prime_numbers.txt"
        e.size shouldBe 7212L
        e.rev shouldBe "a1c10ce0dd78"
        e.contentHash shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        // The same object with the tag is still a file; any other tag is refused.
        DropboxJson.fileEntry(DropboxJson.parse(getMetadataResult.toByteArray())) shouldBe e
        val folderTag = uploadResult.replaceFirst("{", """{".tag": "folder", """)
        DropboxJson.fileEntry(DropboxJson.parse(folderTag.toByteArray())) shouldBe null
    }

    @Test
    fun `polymorphic Metadata needs its tag`() {
        DropboxJson.entry(DropboxJson.parse(getMetadataResult.toByteArray()))!!.size shouldBe 7212L
        DropboxJson.entry(DropboxJson.parse(uploadResult.toByteArray())) shouldBe null
        val deleted = """{".tag": "deleted", "name": "x", "path_lower": "/x", "path_display": "/x"}"""
        DropboxJson.entry(DropboxJson.parse(deleted.toByteArray())) shouldBe null
        val folder = """{".tag": "folder", "name": "math", "path_lower": "/homework/math", "id": "id:x"}"""
        DropboxJson.entry(DropboxJson.parse(folder.toByteArray()))!!.isFolder shouldBe true
    }

    @Test
    fun `the download's Dropbox-API-Result header, ASCII-escaped and untagged, is read as a file`() {
        // As on the wire: the Hebrew letter written as the six ASCII characters backslash-u-0-5-e-a.
        val header = (
            """{"name": "TAV.jpg", "path_lower": "/TAV.jpg", "path_display": "/TAV.jpg",""" +
                """ "id": "id:1", "rev": "a1c10ce0dd78", "size": 3, "server_modified": "2015-05-12T15:50:38Z"}"""
        ).replace("TAV", "\\u05ea")
        header.all { it.code < 0x80 } shouldBe true
        val e = DropboxJson.fileEntry(DropboxJson.parse(header.toByteArray(Charsets.ISO_8859_1)))
        e!!.name shouldBe "ת.jpg"
        e.size shouldBe 3L
    }
}
