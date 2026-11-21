package app.reader

/** Builds a small original EPUB 3 book (with an NCX too) so the reader has something to open. */
object SampleBook {
    const val TITLE = "The Lamp at Gull Point"
    const val AUTHOR = "A Sample Story"

    private val chapters = listOf(
        "The Keeper's Notebook" to listOf(
            "<p>The notebook had a green cloth cover gone soft at the corners, and every page began the same way: the date, the wind, the state of the sea. Ilse had found it in the drawer beneath the chart table on her first evening at Gull Point, wedged behind a tin of matches and a pencil worn down to the length of a thumb.</p>",
            "<p>She read it by the light of the small lamp in the kitchen while the big lamp turned above her. <em>Wind north-east, freshening. Sea moderate. Two trawlers in by six.</em> The handwriting was patient and square, the hand of someone who expected to be read by a stranger one day and wanted to be fair to them.</p>",
            "<p>Nobody in the village could tell her who had written it. The last keeper, they said, had been a quiet man who kept to himself and kept the light, and that was all anyone had ever needed to know about him. He had left in the spring without a word, and the station had stood dark for three weeks before the council found her.</p>",
            "<p>On the last written page there was a list, and it was the list that kept her awake:</p>",
            "<ol><li>Check the clockwork every four hours.</li><li>Polish the lens on Sundays, whatever the weather.</li><li>Answer the visitor at low tide.</li></ol>",
            "<p>The first two she understood. The third she read many times, holding the page closer to the lamp as if more light might change the words.</p>",
            "<p>Outside, the tide was going out. She could hear it pulling at the shingle, drawing the stones down the beach with a sound like a long, slow breath. Somewhere past the point a bell buoy rang twice and was quiet. Ilse closed the notebook, set it square on the table the way she had found it, and climbed the stairs to wind the clock.</p>",
        ),
        "Fog Signals" to listOf(
            "<p>The fog came in on the fourth morning, thick and white and so sudden that it seemed to have been waiting just over the horizon for permission. By breakfast the gulls had stopped calling. By ten the end of the jetty had vanished, and the world had shrunk to a circle of wet grey stone about twenty paces across.</p>",
            "<p>The fog signal was older than the lamp. It lived in a brick shed at the foot of the tower, a pair of great brass horns aimed at the sea, driven by an engine that coughed and complained before it would agree to run.<a epub:type=\"noteref\" href=\"#fn1\">*</a> When it finally caught, the whole headland shook with its voice: two long notes, a pause, two long notes, over and over, every half minute through the day.</p>",
            "<aside epub:type=\"footnote\" id=\"fn1\"><p>The notebook gave careful instructions for starting it, ending with the words: be firm, but kind.</p></aside>",
            "<p>Ilse sat in the shed doorway with a mug of tea gone cold in her hands and listened to the horns call out into nothing. It was a strange kind of conversation. The sea did not answer. The fog did not answer. But every so often, far off, she thought she heard a ship's whistle reply, faint and grateful, before it slid on past the rocks and away.</p>",
            "<blockquote><p>Fog all day. Signal running from 09:40. Engine sound. One vessel heard to the south, answering.</p></blockquote>",
            "<p>She wrote it in the notebook beneath the last keeper's square hand, and was surprised by how natural it felt, as if the book had been waiting for her handwriting the way the station had been waiting for her light.</p>",
            "<hr/>",
            "<p>The fog lifted at dusk, all at once, like a curtain drawn back. The sea lay flat and pink under a clean sky, and the first star came out over the point exactly where the notebook said it would.</p>",
        ),
        "A Visitor at Low Tide" to listOf(
            "<p>Low tide fell a little after midnight. Ilse told herself she was only going down to check the jetty lines, and took the storm lantern, and a coat, and the notebook in the coat's deep pocket.</p>",
            "<p>The beach at low water was a different country. Rock pools shone under the lantern like dropped coins. Weed lay in long dark ribbons across the sand, and the air smelled of salt and iron and something green. Far out, where the water still moved, the reflection of the great lamp swept across the bay every twelve seconds, a road of light that appeared and vanished and appeared again.</p>",
            "<p>She saw the boat when the beam passed over it: a small wooden dinghy pulled up on the sand below the point, its oars shipped neatly, its painter tied to an iron ring she had never noticed before. Beside it, sitting on an upturned crate as though it were the most ordinary thing in the world, was an old woman in a yellow oilskin, eating an apple.</p>",
            "<p>“You'll be the new keeper,” the woman said. “You took your time.”</p>",
            "<p>“I'm sorry,” said Ilse, who did not know what else to say. “Nobody told me you'd be coming.”</p>",
            "<p>“Nobody ever does. That's why it's written down.” The woman finished her apple, considered the core, and threw it in a long arc into the dark water. “He used to bring a flask of something hot. I don't suppose you thought to?”</p>",
            "<p>Ilse had not. But she sat down on the sand beside the crate all the same, and set the lantern between them, and when the woman began to talk about the light, and the ships, and the long patient work of keeping a thing burning for people who would never know your name, she listened until the tide turned.</p>",
        ),
        "The Lamp Room" to listOf(
            "<p>In the morning the dinghy was gone and the iron ring was only an iron ring, rusted and old, the kind of thing a person could walk past for years. Ilse climbed the tower with her polishing cloths even though it was a Thursday.</p>",
            "<p>The lamp room was the best place in the world on a clear day. The lens stood in the middle of it like a great glass beehive, ring upon ring of prisms, each one catching the sun and breaking it into colour, so that the white walls were covered in small moving rainbows. She polished slowly, ring by ring, and thought about the woman in the yellow coat.</p>",
            "<p>When she had finished she sat on the gallery outside with the notebook open on her knees. The sea was bright and busy. A ferry crossed the bay with its wake spreading behind it like a fan. She uncapped her pen and, after a long moment, added a fourth line to the list:</p>",
            "<ol start=\"4\"><li>Bring a flask.</li></ol>",
            "<p>Then she turned to a clean page and wrote the date, and the wind, and the state of the sea, in a hand she tried to make patient and square, for whoever would come after her and need to be told.</p>",
            "<p class=\"center\"><strong>The End</strong></p>",
        ),
    )

    fun build(): ByteArray {
        val files = mutableListOf<Pair<String, ByteArray>>()
        files += "mimetype" to "application/epub+zip".encodeToByteArray()
        files += "META-INF/container.xml" to """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".encodeToByteArray()

        val manifest = StringBuilder()
        val spine = StringBuilder()
        val navItems = StringBuilder()
        val ncxPoints = StringBuilder()
        chapters.forEachIndexed { i, (title, paras) ->
            val n = i + 1
            val name = "chapter$n.xhtml"
            files += "OEBPS/text/$name" to """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>$title</title><link rel="stylesheet" type="text/css" href="../style.css"/></head>
<body>
<section epub:type="chapter">
<h2>Chapter $n</h2>
<h1>$title</h1>
${paras.mapIndexed { k, para -> if (k == 0) para.replaceFirst("<p>", "<p class=\"first\">") else para }.joinToString("\n")}
</section>
</body>
</html>""".encodeToByteArray()
            manifest.append("""    <item id="c$n" href="text/$name" media-type="application/xhtml+xml"/>""").append('\n')
            spine.append("""    <itemref idref="c$n"/>""").append('\n')
            navItems.append("""      <li><a href="text/$name">$title</a></li>""").append('\n')
            ncxPoints.append("""    <navPoint id="np$n" playOrder="$n"><navLabel><text>$title</text></navLabel><content src="text/$name"/></navPoint>""").append('\n')
        }
        files += "OEBPS/style.css" to """
/* the sample book's own style, read by the reader's stylesheet support */
p { margin: 0; text-indent: 1.5em; }
p.first, p.center, blockquote p { text-indent: 0; }
h1 { margin-top: 0.4em; margin-bottom: 1.4em; }
h2 { margin-bottom: 0.2em; font-weight: normal; }
blockquote { font-style: italic; }
""".trimIndent().encodeToByteArray()
        files += "OEBPS/nav.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Contents</title></head>
<body><nav epub:type="toc"><h1>Contents</h1><ol>
$navItems    </ol></nav></body></html>""".encodeToByteArray()
        files += "OEBPS/toc.ncx" to """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head/><docTitle><text>$TITLE</text></docTitle>
  <navMap>
$ncxPoints  </navMap>
</ncx>""".encodeToByteArray()
        files += "OEBPS/package.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">sample-gull-point</dc:identifier>
    <dc:title>$TITLE</dc:title>
    <dc:creator>$AUTHOR</dc:creator>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="style.css" media-type="text/css"/>
$manifest  </manifest>
  <spine toc="ncx">
$spine  </spine>
</package>""".encodeToByteArray()
        return zipEntries(files)
    }
}
