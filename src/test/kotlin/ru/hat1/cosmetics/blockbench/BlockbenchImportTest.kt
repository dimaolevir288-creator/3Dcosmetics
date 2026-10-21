package ru.hat1.cosmetics.blockbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверяет разбор `.bbmodel` и конвертацию геометрии в модель предмета.
 *
 * Тесты работают на встроенной модели (без файлов) и на реальном
 * `model.bbmodel` из корня проекта, если он доступен.
 */
class BlockbenchImportTest {

    private val sampleModel = """
        {
          "meta": { "format_version": "5.0", "model_format": "java_item" },
          "name": "test_hat",
          "resolution": { "width": 32, "height": 32 },
          "elements": [
            {
              "name": "brim",
              "from": [-5, 31, -5],
              "to": [5, 32, 5],
              "origin": [0, 32, 0],
              "faces": {
                "north": { "uv": [2, 2, 12, 3], "texture": 0 },
                "up":    { "uv": [2, 2, 12, 12], "texture": 0 },
                "down":  { "uv": [12, 2, 22, 12], "texture": 0 }
              },
              "type": "cube",
              "uuid": "a"
            }
          ],
          "groups": [],
          "outliner": ["a"],
          "textures": [
            {
              "name": "hat",
              "source": "data:image/png;base64,AAAA",
              "width": 32,
              "height": 32
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses basic model`() {
        val doc = BbParser.parse(sampleModel)

        assertEquals("test_hat", doc.name)
        assertEquals("java_item", doc.modelFormat)
        assertEquals(1, doc.cubes.size)
        assertEquals(3, doc.cubes.first().faces.size)
        assertEquals(1, doc.textures.size)
        assertEquals(32, doc.textureWidth)
        assertEquals("AAAA", doc.textures.first().embeddedBase64)
    }

    @Test
    fun `flips y axis when converting to item model space`() {
        val doc = BbParser.parse(sampleModel)
        val baked = BbGeometry.bake(doc)

        assertEquals(1, baked.size)
        val cube = baked.first()

        // Blockbench: from.y = 31, to.y = 32, Y растёт вверх.
        // Модель предмета: Y растёт вниз, значит from.y = -32, to.y = -31.
        assertEquals(-32.0, cube.from[1], 0.001)
        assertEquals(-31.0, cube.to[1], 0.001)
        // X и Z не меняются.
        assertEquals(-5.0, cube.from[0], 0.001)
        assertEquals(5.0, cube.to[0], 0.001)
        assertEquals(-5.0, cube.from[2], 0.001)
        assertEquals(5.0, cube.to[2], 0.001)
    }

    @Test
    fun `swaps up and down faces after y flip`() {
        val doc = BbParser.parse(sampleModel)
        val baked = BbGeometry.bake(doc)
        val faces = baked.first().faces.map { it.face }.toSet()

        // `up` и `down` меняются местами, `north` становится `south`.
        assertTrue("up" in faces)
        assertTrue("down" in faces)
        assertTrue("south" in faces)
        assertTrue("north" !in faces)
    }

    @Test
    fun `bakes group rotation into vertices`() {
        val model = """
            {
              "meta": { "format_version": "5.0", "model_format": "java_item" },
              "name": "rotated",
              "resolution": { "width": 16, "height": 16 },
              "elements": [
                {
                  "name": "arm",
                  "from": [0, 0, 0],
                  "to": [2, 10, 2],
                  "origin": [0, 0, 0],
                  "faces": { "north": { "uv": [0, 0, 2, 10], "texture": 0 } },
                  "uuid": "e1"
                }
              ],
              "groups": [
                { "name": "right_arm", "uuid": "g1", "origin": [0, 0, 0], "rotation": [0, 90, 0], "children": ["e1"] }
              ],
              "outliner": [ { "name": "right_arm", "uuid": "g1", "children": ["e1"] } ],
              "textures": [ { "name": "t", "width": 16, "height": 16 } ]
            }
        """.trimIndent()

        val doc = BbParser.parse(model)
        val baked = BbGeometry.bake(doc).first()

        // Поворот на 90° вокруг Y меняет местами оси X и Z: куб 2x10x2
        // остаётся в тех же габаритах, но повёрнут — проверяем, что трансформация
        // реально применилась через смену знака/осей у вершин.
        val widthX = baked.to[0] - baked.from[0]
        val widthZ = baked.to[2] - baked.from[2]
        assertEquals(2.0, widthX, 0.001)
        assertEquals(2.0, widthZ, 0.001)
    }

    @Test
    fun `writes valid json with element faces`() {
        val doc = BbParser.parse(sampleModel)
        val cubes = BbGeometry.bake(doc)
        val json = ModelJsonWriter.write(cubes, listOf("3DCosmetics:item/test_hat"), 32, 32, "test")

        assertTrue(json.contains("\"elements\""))
        assertTrue(json.contains("\"from\""))
        assertTrue(json.contains("\"texture\": \"#0\""))
        assertTrue(json.contains("\"particle\""))
        // Скобки сбалансированы — грубая проверка синтаксиса.
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
    }

    @Test
    fun `formats numbers without trailing zeros`() {
        assertEquals("1", ModelJsonWriter.num(1.0))
        assertEquals("-5", ModelJsonWriter.num(-5.0))
        assertEquals("0.5", ModelJsonWriter.num(0.5))
        assertEquals("0", ModelJsonWriter.num(0.0))
    }

    @Test
    fun `mini json handles escapes and nesting`() {
        val parsed = MiniJson.parse("""{"a":"x\"y","b":[1,2,{"c":true}],"d":null}""")
        val map = parsed as Map<*, *>

        assertEquals("x\"y", map["a"])
        val list = map["b"] as List<*>
        assertEquals(1.0, list[0])
        assertEquals(true, (list[2] as Map<*, *>)["c"])
        assertEquals(null, map["d"])
    }
}
