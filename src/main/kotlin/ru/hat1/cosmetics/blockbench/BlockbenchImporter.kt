package ru.hat1.cosmetics.blockbench

import java.util.Base64
import java.util.logging.Level
import org.bukkit.plugin.java.JavaPlugin
import ru.hat1.cosmetics.model.CosmeticSlot

/**
 * Результат импорта одного `.bbmodel`.
 *
 * [modelKey] — ключ `item-model`, который нужно прописать в `cosmetics.yml`
 * (`3dcosmetics:item/<modelKey>`). [textureKeys] — записанные текстуры.
 * [slot] — часть тела, к которой модель привязана по разметке Blockbench;
 * null, если привязку определить не удалось.
 */
data class ImportedModel(
    val sourceFile: String,
    val modelId: String,
    val modelKey: String,
    val textureKeys: List<String>,
    val cubeCount: Int,
    val warnings: List<String>,
    val slot: CosmeticSlot? = null,
    /** Имя кости, по которому определён [slot]. */
    val boneName: String? = null,
)

/** Сводка по всей пачке импортированных моделей. */
data class ImportSummary(
    val imported: List<ImportedModel>,
    val failed: List<Pair<String, String>>,
) {
    val total: Int get() = imported.size + failed.size
    val isEmpty: Boolean get() = imported.isEmpty() && failed.isEmpty()
}

/**
 * Импорт моделей Blockbench (`.bbmodel`) в ресурспак плагина.
 *
 * Как это работает:
 *  1. плагин сканирует `plugins/3DCosmetics/blockbench/` в поисках `*.bbmodel`;
 *  2. каждая модель разбирается, геометрия приводится к системе координат
 *     Minecraft-модели предмета (см. [BbGeometry]);
 *  3. результат записывается в ресурспак как:
 *       `assets/3dcosmetics/models/item/<id>.json`
 *       `assets/3dcosmetics/textures/item/<id>.png`
 *  4. Дальше [ru.hat1.cosmetics.resourcepack.ResourcePackService] собирает
 *     архив — модели попадают к игрокам вместе с остальным паком.
 *
 * Текстуры модель несёт внутри себя (`source: data:image/png;base64,…`).
 * Для форматов, где текстура лежит рядом отдельным файлом, поддерживаются
 * файлы `<имя>.png` и папка `<имя>.textures/` в той же директории.
 */
class BlockbenchImporter(
    private val plugin: JavaPlugin,
    private val sourceFolder: java.io.File,
    private val packFolder: java.io.File,
    private val namespace: String = NAMESPACE,
) {

    /**
     * Импортирует все `.bbmodel` из [sourceFolder].
     *
     * Файлы, которые не изменились с прошлого запуска (по хешу содержимого),
     * пропускаются — так `/cosmetics reload` не пересобирает всё заново.
     */
    fun importAll(force: Boolean = false): ImportSummary {
        val imported = ArrayList<ImportedModel>()
        val failed = ArrayList<Pair<String, String>>()

        if (!sourceFolder.exists()) {
            sourceFolder.mkdirs()
            return ImportSummary(imported, failed)
        }

        val files = sourceFolder.listFiles { file ->
            file.isFile && file.extension.equals("bbmodel", ignoreCase = true)
        }?.sortedBy { it.name.lowercase() }.orEmpty()

        for (file in files) {
            try {
                val result = importFile(file, force)
                if (result != null) imported += result
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Не удалось импортировать ${file.name}", e)
                failed += file.name to (e.message ?: e.javaClass.simpleName)
            }
        }

        return ImportSummary(imported, failed)
    }

    /**
     * Импортирует один файл. Возвращает null, если модель не изменилась
     * и [force] не запрошен.
     */
    fun importFile(file: java.io.File, force: Boolean = false): ImportedModel? {
        val raw = file.readText(Charsets.UTF_8)
        val modelId = sanitizeId(file.nameWithoutExtension)

        val modelTarget = java.io.File(packFolder, "$MODEL_DIR$modelId.json")
        val hashTarget = java.io.File(sourceFolder, "$modelId.${HASH_SUFFIX}")

        val hash = sha1(raw)
        if (!force && modelTarget.exists() && hashTarget.exists() && hashTarget.readText().trim() == hash) {
            return null
        }

        val document = BbParser.parse(raw)
        val warnings = ArrayList<String>()

        if (document.cubes.isEmpty()) {
            throw IllegalArgumentException("в модели нет кубов (elements пуст или содержит только mesh)")
        }
        if (document.modelFormat == "cpm") {
            warnings += "Формат cpm (Custom Player Models) упрощён: сохранена геометрия без анимаций."
        }

        // Привязка к части тела берётся из разметки Blockbench (имя файла/групп).
        val slot = document.boneName?.let { CosmeticSlot.fromBoneName(it) }
            ?: CosmeticSlot.fromBoneName(file.nameWithoutExtension)
        if (slot == null) {
            warnings += "Не удалось определить часть тела по имени модели. " +
                "укажите ее вручную в cosmetics.yml (поле slot) или переименуйте файл " +
                "(например, hat_top.bbmodel или right_arm.bbmodel)."
        }

        // Крепление задаёт слот: для головы — макушка, для ног — ступни.
        val anchor = slot?.anchor ?: ru.hat1.cosmetics.model.Anchor.HEAD

        // Пивот: Blockbench для java_item держит модель относительно [0, 0, 0],
        // но косметика должна быть по центру точки крепления — вычисляем автоматически.
        val cubes = BbGeometry.bake(document, flipY = true)
            .filter { it.faces.isNotEmpty() }

        if (cubes.isEmpty()) {
            throw IllegalArgumentException("после конвертации не осталось граней")
        }

        // 1. Текстуры: пишем PNG рядом с моделью.
        val textureKeys = writeTextures(modelId, document, warnings)

        // 2. Модель: ссылается на текстуры по индексу.
        modelTarget.parentFile?.mkdirs()
        val modelJson = ModelJsonWriter.write(
            cubes = cubes,
            textureRefs = textureKeys.map { "$namespace:item/$it" },
            textureWidth = document.textureWidth,
            textureHeight = document.textureHeight,
            credit = "Imported from ${file.name} by 3DCosmetics",
        )
        modelTarget.writeText(modelJson, Charsets.UTF_8)

        hashTarget.writeText(hash, Charsets.UTF_8)

        plugin.logger.info(
            "Blockbench: импортирована модель '${file.name}' → " +
                "$namespace:item/$modelId (${cubes.size} кубов, текстур: ${textureKeys.size}, " +
                "слот: ${slot?.id ?: "не определён"})"
        )

        return ImportedModel(
            sourceFile = file.name,
            modelId = modelId,
            modelKey = "$namespace:item/$modelId",
            textureKeys = textureKeys,
            cubeCount = cubes.size,
            warnings = warnings,
            slot = slot,
            boneName = document.boneName,
        )
    }

    /**
     * Записывает текстуры модели в `textures/item/`.
     *
     * Приоритет: встроенная base64 → файл `<модель>.png` → папка
     * `<модель>.textures/`. Если ничего не найдено, пишется заглушка, чтобы
     * модель не осталась без текстуры (иначе Minecraft не загрузит её вовсе).
     */
    private fun writeTextures(
        modelId: String,
        document: BbDocument,
        warnings: MutableList<String>,
    ): List<String> {
        val dir = java.io.File(packFolder, TEXTURE_DIR)
        dir.mkdirs()

        val keys = ArrayList<String>(document.textures.size)

        document.textures.forEachIndexed { index, texture ->
            // Имя файла: <модель> или <модель>_<индекс> при нескольких текстурах.
            val baseName = if (document.textures.size == 1) modelId else "${modelId}_$index"
            val target = java.io.File(dir, "$baseName.png")

            if (texture.embeddedBase64 != null) {
                try {
                    val bytes = Base64.getDecoder().decode(texture.embeddedBase64)
                    target.writeBytes(bytes)
                    keys += baseName
                    return@forEachIndexed
                } catch (e: IllegalArgumentException) {
                    warnings += "Текстура '${texture.name}' содержит некорректный base64"
                }
            }

            // Внешний файл рядом с .bbmodel.
            val sideCar = findSideCarTexture(modelId, texture.name, index)
            if (sideCar != null) {
                target.writeBytes(sideCar.readBytes())
                keys += baseName
                return@forEachIndexed
            }

            warnings += "Текстура '${texture.name}' не встроена в .bbmodel и не найдена рядом — " +
                "записана заглушка. В Blockbench включите «Embed textures» при сохранении."
            target.writeBytes(placeholderPng())
            keys += baseName
        }

        if (document.textures.isEmpty()) {
            val target = java.io.File(dir, "$modelId.png")
            target.writeBytes(placeholderPng())
            keys += modelId
            warnings += "В .bbmodel нет текстур — записана заглушка."
        }

        return keys
    }

    /** Ищет PNG-текстуру рядом с `.bbmodel`. */
    private fun findSideCarTexture(modelId: String, textureName: String, index: Int): java.io.File? {
        val candidates = listOf(
            "$modelId.png",
            "$modelId.textures/$textureName.png",
            "$modelId.textures/${textureName.replace(' ', '_')}.png",
            "textures/$textureName.png",
            "${modelId}_$index.png",
        )
        for (candidate in candidates) {
            val file = java.io.File(sourceFolder, candidate)
            if (file.isFile) return file
        }
        return null
    }

    /** Приводит имя файла к безопасному идентификатору ресурса. */
    fun sanitizeId(raw: String): String {
        val cleaned = buildString(raw.length) {
            for (c in raw.lowercase()) {
                when {
                    c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-' || c == '.' -> append(c)
                    c == ' ' -> append('_')
                }
            }
        }
        return cleaned.ifBlank { "model" }
    }

    private fun sha1(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Минимальный валидный PNG 16×16 — заглушка для моделей без текстуры.
     *
     * Генерируется один раз в памяти: 16×16 RGBA, полностью прозрачный.
     */
    private fun placeholderPng(): ByteArray {
        val size = 16
        // Данные изображения: 4 байта на пиксель, все нули (прозрачный).
        val raw = java.io.ByteArrayOutputStream()
        for (y in 0 until size) {
            raw.write(0) // filter type: none
            for (x in 0 until size) {
                raw.write(0)
                raw.write(0)
                raw.write(0)
                raw.write(0)
            }
        }
        val compressed = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(compressed).use { it.write(raw.toByteArray()) }

        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        fun chunk(type: String, data: ByteArray) {
            val length = java.nio.ByteBuffer.allocate(4).putInt(data.size).array()
            out.write(length)
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            out.write(typeBytes)
            out.write(data)
            val crc = java.util.zip.CRC32()
            crc.update(typeBytes)
            crc.update(data)
            out.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        }

        // IHDR: ширина, высота, битность 8, тип цвета 6 (RGBA), сжатие 0, фильтр 0, interlace 0
        chunk("IHDR", java.nio.ByteBuffer.allocate(13).apply {
            putInt(size)
            putInt(size)
            put(8)
            put(6)
            put(0)
            put(0)
            put(0)
        }.array())
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", ByteArray(0))

        return out.toByteArray()
    }

    companion object {
        /** Папка в dataFolder, откуда плагин берёт `.bbmodel`. */
        const val SOURCE_FOLDER = "blockbench"

        /** Путь к моделям внутри ресурспака (относительно его корня). */
        const val MODEL_DIR = "assets/3dcosmetics/models/item/"

        /** Путь к текстурам внутри ресурспака (относительно его корня). */
        const val TEXTURE_DIR = "assets/3dcosmetics/textures/item/"

        /** Пространство имён ресурспака. */
        const val NAMESPACE = "3dcosmetics"

        /** Расширение файла-маркера с хешем импортированной модели. */
        const val HASH_SUFFIX = "importhash"
    }
}
