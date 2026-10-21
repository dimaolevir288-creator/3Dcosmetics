package ru.hat1.cosmetics.blockbench

/**
 * Разбор и представление геометрии в стиле Blockbench / Minecraft-моделей.
 *
 * Здесь нет ссылок ни на Gson, ни на какой-либо JSON-бэкенд: парсер написан
 * вручную, потому что плагин не должен тянуть лишние библиотеки в jar.
 */

/**
 * Грань куба: [face] — имя грани Minecraft (`north`, `up`, …),
 * [uv] — координаты `[u1, v1, u2, v2]` в пикселях текстуры.
 */
internal data class BbFace(val face: String, val uv: List<Double>)

/** Куб (элемент) модели. */
internal data class BbCube(
    val name: String,
    val from: DoubleArray,
    val to: DoubleArray,
    val origin: DoubleArray,
    val rotation: DoubleArray,
    val faces: List<BbFace>,
    /** Индекс текстуры, на которую ссылаются грани. */
    val textureIndex: Int,
    /** Явное отражение UV (Blockbench `mirror_uv`). */
    val mirrorUv: Boolean,
)

/** Группа кубов (кость). Влияет на поворот и видимость. */
internal data class BbGroup(
    val name: String,
    val origin: DoubleArray,
    val rotation: DoubleArray,
    /** Имена дочерних элементов из outliner-а. */
    val children: List<String>,
    val parent: String?,
)

/**
 * Текстура модели. [embeddedBase64] заполнен, если Blockbench сохранил
 * изображение прямо в `.bbmodel` (`source: "data:image/png;base64,..."`).
 */
internal data class BbTexture(
    val name: String,
    val embeddedBase64: String?,
    val width: Int,
    val height: Int,
)

/** Разобранная модель Blockbench. */
internal data class BbDocument(
    val name: String,
    val cubes: List<BbCube>,
    val groups: Map<String, BbGroup>,
    val rootOutliner: List<String>,
    val textures: List<BbTexture>,
    val textureWidth: Int,
    val textureHeight: Int,
    /** `java_item`, `java_block`, `cpm`, … */
    val modelFormat: String,
    /**
     * Имя кости, к которой крепится модель (`head`, `right_arm`, …), если его
     * можно определить по разметке Blockbench. null — не определено.
     */
    val boneName: String? = null,
)

/**
 * Минимальный JSON-парсер.
 *
 * Возвращает структуру из [Map], [List], [String], [Double], [Boolean] и null.
 * Этого достаточно для `.bbmodel`, а лишние зависимости в плагин не попадают.
 */
internal object MiniJson {

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        return reader.readValue()
    }

    private class Reader(private val text: String) {
        private var pos = 0

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            if (pos >= text.length) throw IllegalArgumentException("Неожиданный конец JSON")
            return when (val c = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    if (c == '-' || c.isDigit()) readNumber()
                    else throw IllegalArgumentException("Неожиданный символ '$c' в позиции $pos")
                }
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, pos)) {
                throw IllegalArgumentException("Ожидалось '$literal' в позиции $pos")
            }
            pos += literal.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }

                    else -> throw IllegalArgumentException("Ожидалось ',' или '}' в позиции $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                result += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }

                    else -> throw IllegalArgumentException("Ожидалось ',' или ']' в позиции $pos")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) throw IllegalArgumentException("Незакрытая строка")
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }

                            else -> throw IllegalArgumentException("Неизвестная escape-последовательность '\\$esc'")
                        }
                    }

                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            return text.substring(start, pos).toDouble()
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun expect(c: Char) {
            if (pos >= text.length || text[pos] != c) {
                throw IllegalArgumentException("Ожидалось '$c' в позиции $pos")
            }
            pos++
        }
    }
}

/** Хелперы для безопасного чтения распарсенного JSON. */
internal object JsonAccess {

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?>? {
        val map = value as? Map<*, *> ?: return null
        return map as Map<String, Any?>
    }

    fun arr(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

    fun str(map: Map<String, Any?>?, key: String): String? =
        (map?.get(key) as? String)?.takeIf { it.isNotEmpty() }

    fun num(map: Map<String, Any?>?, key: String, fallback: Double = 0.0): Double =
        (map?.get(key) as? Number)?.toDouble() ?: fallback

    fun bool(map: Map<String, Any?>?, key: String, fallback: Boolean = false): Boolean =
        (map?.get(key) as? Boolean) ?: fallback

    fun numArray(map: Map<String, Any?>?, key: String): DoubleArray {
        val list = arr(map?.get(key))
        val result = DoubleArray(list.size)
        for (i in list.indices) result[i] = (list[i] as? Number)?.toDouble() ?: 0.0
        return result
    }

    fun triple(map: Map<String, Any?>?, key: String): DoubleArray {
        val raw = numArray(map, key)
        return DoubleArray(3) { i -> if (i < raw.size) raw[i] else 0.0 }
    }
}

/**
 * Разбор `.bbmodel` в [BbDocument].
 *
 * Поддерживаются форматы `java_item`, `java_block`, `free`, `cpm`
 * и любые другие, где геометрия описана в `elements` / `groups` / `outliner`.
 */
internal object BbParser {

    /** Порядок граней, совпадающий с Minecraft-моделями. */
    val FACE_ORDER = listOf("north", "east", "south", "west", "up", "down")

    fun parse(rawJson: String): BbDocument {
        val root = JsonAccess.obj(MiniJson.parse(rawJson))
            ?: throw IllegalArgumentException("Корень .bbmodel должен быть JSON-объектом")

        val meta = JsonAccess.obj(root["meta"])
        val resolution = JsonAccess.obj(root["resolution"])
        val name = JsonAccess.str(root, "name") ?: "blockbench_model"
        val modelFormat = JsonAccess.str(meta, "model_format") ?: "java_item"

        val textures = JsonAccess.arr(root["textures"]).mapIndexed { index, raw ->
            val tex = JsonAccess.obj(raw)
            BbTexture(
                name = JsonAccess.str(tex, "name") ?: "texture_$index",
                embeddedBase64 = JsonAccess.str(tex, "source")?.let(::extractBase64),
                width = JsonAccess.num(tex, "width", 16.0).toInt(),
                height = JsonAccess.num(tex, "height", 16.0).toInt(),
            )
        }

        // Blockbench пишет размер UV-полотна в resolution модели. Если его нет,
        // берём размер первой текстуры, чтобы UV не «съехали».
        val texWidth = JsonAccess.num(resolution, "width", textures.firstOrNull()?.width?.toDouble() ?: 16.0).toInt()
        val texHeight = JsonAccess.num(resolution, "height", textures.firstOrNull()?.height?.toDouble() ?: 16.0).toInt()

        val groups = LinkedHashMap<String, BbGroup>()
        for (raw in JsonAccess.arr(root["groups"])) {
            val map = JsonAccess.obj(raw) ?: continue
            val groupName = JsonAccess.str(map, "name") ?: continue
            val children = JsonAccess.arr(map["children"]).mapNotNull { child ->
                when (child) {
                    is String -> child
                    is Map<*, *> -> JsonAccess.str(JsonAccess.obj(child), "uuid")
                    else -> null
                }
            }
            groups[groupName] = BbGroup(
                name = groupName,
                origin = JsonAccess.triple(map, "origin"),
                rotation = JsonAccess.triple(map, "rotation"),
                children = children,
                parent = null,
            )
        }

        val rootOutliner = JsonAccess.arr(root["outliner"]).mapNotNull { entry ->
            when (entry) {
                is String -> entry
                is Map<*, *> -> JsonAccess.str(JsonAccess.obj(entry), "name")
                else -> null
            }
        }

        // Проставляем родителей по outliner-у, чтобы уметь поднимать повороты.
        val parents = LinkedHashMap<String, String>()

        fun walkOutliner(entries: List<Any?>, parent: String?) {
            for (entry in entries) {
                val map = JsonAccess.obj(entry) ?: continue
                val entryName = JsonAccess.str(map, "name") ?: continue
                if (parent != null) parents[entryName] = parent
                walkOutliner(JsonAccess.arr(map["children"]), entryName)
            }
        }
        walkOutliner(JsonAccess.arr(root["outliner"]), null)

        val groupsWithParents = groups.mapValues { (key, group) ->
            group.copy(parent = parents[key])
        }

        val cubes = ArrayList<BbCube>()
        for (raw in JsonAccess.arr(root["elements"])) {
            val map = JsonAccess.obj(raw) ?: continue
            // Меш-геометрия не выражается кубами — такие элементы пропускаем.
            if (JsonAccess.str(map, "type")?.equals("mesh", ignoreCase = true) == true) continue
            if (!JsonAccess.bool(map, "export", true)) continue

            val elementName = JsonAccess.str(map, "name") ?: "cube_${cubes.size}"
            val from = JsonAccess.numArray(map, "from")
            val to = JsonAccess.numArray(map, "to")
            if (from.size < 3 || to.size < 3) continue

            val faces = ArrayList<BbFace>()
            var textureIndex = 0
            val facesMap = JsonAccess.obj(map["faces"])
            if (facesMap != null) {
                for (faceName in FACE_ORDER) {
                    val face = JsonAccess.obj(facesMap[faceName]) ?: continue
                    val uvRaw = JsonAccess.arr(face["uv"])
                    if (uvRaw.size < 4) continue
                    val uv = uvRaw.map { (it as? Number)?.toDouble() ?: 0.0 }
                    textureIndex = JsonAccess.num(face, "texture", 0.0).toInt()
                    faces += BbFace(faceName, uv)
                }
            }

            cubes += BbCube(
                name = elementName,
                from = from,
                to = to,
                origin = JsonAccess.triple(map, "origin"),
                rotation = JsonAccess.triple(map, "rotation"),
                faces = faces,
                textureIndex = textureIndex,
                mirrorUv = JsonAccess.bool(map, "mirror_uv"),
            )
        }

        return BbDocument(
            name = name,
            cubes = cubes,
            groups = groupsWithParents,
            rootOutliner = rootOutliner,
            textures = textures,
            textureWidth = texWidth,
            textureHeight = texHeight,
            modelFormat = modelFormat,
            boneName = detectBone(rootOutliner, groupsWithParents, name),
        )
    }

    /**
     * Определяет кость, к которой относится модель — из имени самого файла.
     *
     * Blockbench не хранит «слот косметики» как поле, поэтому опираемся на
     * то, что автор модели и так указывает в разметке:
     *
     *  1. имя файла/модели содержит тело: `hat_top`, `right_arm_v2`,
     *     `cape_red` — побеждает самый специфичный токен;
     *  2. имена групп outliner-а: если все кубы лежат в одной группе, её имя
     *     и есть кость;
     *  3. если в файле ровно одна группа и она называется как часть тела — берём
     *     её.
     *
     * Пустая строка или null — привязку определить не удалось.
     */
    fun detectBone(rootOutliner: List<String>, groups: Map<String, BbGroup>, modelName: String? = null): String? {
        // 1. Имя файла — самый надёжный признак для готовой косметики.
        if (!modelName.isNullOrBlank() && looksLikeBone(modelName)) {
            return modelName.lowercase().trim()
        }
        // 2. Единственная группа верхнего уровня.
        val topGroups = rootOutliner.filter { it in groups }
        if (topGroups.size == 1) {
            val name = topGroups.first()
            if (looksLikeBone(name)) return name
        }
        // 3. Ровно одна группа во всём файле.
        if (topGroups.isEmpty() && groups.size == 1) {
            val name = groups.keys.first()
            if (looksLikeBone(name)) return name
        }
        return null
    }

    /** Возвращает true, если имя похоже на часть тела игрока. */
    private fun looksLikeBone(raw: String): Boolean {
        val normalized = raw.lowercase().filter { it.isLetterOrDigit() }
        if (normalized.isEmpty()) return false
        val keywords = listOf(
            "head", "hat", "arm", "leg", "foot", "feet", "boot",
            "body", "torso", "chest", "cape", "wing", "back",
        )
        return keywords.any { normalized.contains(it) }
    }

    /** Достаёт base64 из `data:image/png;base64,....`. */
    private fun extractBase64(source: String): String? {
        val marker = "base64,"
        val index = source.indexOf(marker)
        if (index < 0) return null
        return source.substring(index + marker.length).trim().takeIf { it.isNotEmpty() }
    }
}
