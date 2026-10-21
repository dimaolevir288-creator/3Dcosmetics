package ru.hat1.cosmetics.blockbench

import ru.hat1.cosmetics.blockbench.BbParser.FACE_ORDER
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Приведение геометрии Blockbench к системе координат Minecraft-модели предмета.
 *
 * Ключевые отличия двух систем:
 *  - Blockbench (`java_item`, `cpm`) растёт по Y **вверх**, ванильная модель
 *    предмета — по Y **вниз**;
 *  - в Blockbench есть кости (`groups`) с собственным origin и поворотом,
 *    а у модели предмета поворотов нет вовсе — только «запечённые» вершины.
 *
 * Поэтому кости «запекаются»: каждая вершина куба прогоняется через цепочку
 * трансформаций (поворот кости → поворот родителя → … , затем поворот и
 * смещение самого куба) и попадает в итоговый `elements` уже в мировых
 * координатах. Так повороты костей сохраняются, а ограничение ванильного
 * формата (нет вращения элементов) обходится без потери геометрии.
 */
internal object BbGeometry {

    /** Точка в пространстве модели (пиксели Blockbench). */
    data class Vec3(val x: Double, val y: Double, val z: Double) {
        operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)

        operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)

        operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)

        companion object {
            val ZERO = Vec3(0.0, 0.0, 0.0)
        }
    }

    /** Итоговый куб в системе координат Minecraft-модели предмета. */
    data class BakedCube(
        val name: String,
        /** `from` в формате Minecraft (Y вниз). */
        val from: DoubleArray,
        /** `to` в формате Minecraft (Y вниз). */
        val to: DoubleArray,
        /** Грани с уже переназначенными именами и UV. */
        val faces: List<BbFace>,
        /** Индекс текстуры, на которую ссылаются грани. */
        val textureIndex: Int,
    )

    /**
     * Готовит кубы к записи в JSON модели.
     *
     * Пивот считается автоматически: по X/Z — центр габаритов всей модели,
     * по Y — верх модели. Это делает результат предсказуемым для косметики:
     * шляпа оказывается по центру головы, плащ — симметрично относительно спина.
     *
     * @param pivot явный пивот в пикселях Blockbench; null — вычислить самому.
     * @param flipY разворачивать ли Y (true для перехода Blockbench → предмет).
     */
    fun bake(
        document: BbDocument,
        pivot: Vec3? = null,
        flipY: Boolean = true,
    ): List<BakedCube> {
        val effectivePivot = pivot ?: autoPivot(document)
        // Тот же пивот, но в системе координат модели предмета (Y вниз).
        val modelPivot = if (flipY) {
            Vec3(effectivePivot.x, -effectivePivot.y, effectivePivot.z)
        } else {
            effectivePivot
        }
        val result = ArrayList<BakedCube>(document.cubes.size)

        for (cube in document.cubes) {
            // Матрица накопленных трансформаций от корня до этого куба.
            val parentChain = groupChain(document, cube.name)

            val corners = ArrayList<Vec3>(8)
            for (x in doubleArrayOf(cube.from[0], cube.to[0])) {
                for (y in doubleArrayOf(cube.from[1], cube.to[1])) {
                    for (z in doubleArrayOf(cube.from[2], cube.to[2])) {
                        var point = Vec3(x, y, z)
                        // 1. Поворот самого куба вокруг его origin.
                        point = rotateAround(point, cube.origin, cube.rotation)
                        // 2. Повороты костей от ближайшей к корневой.
                        for (group in parentChain) {
                            point = rotateAround(point, group.origin, group.rotation)
                        }
                        // 3. Переход в систему предмета: Y вниз.
                        // Пивот ещё НЕ вычтён — сначала инверсия, чтобы пивот
                        // оставался в системе Blockbench.
                        if (flipY) point = Vec3(point.x, -point.y, point.z)
                        // 4. Сдвиг относительно пивота модели (в системе предмета).
                        point = point - modelPivot
                        corners += point
                    }
                }
            }

            val minX = corners.minOf { it.x }
            val maxX = corners.maxOf { it.x }
            val minY = corners.minOf { it.y }
            val maxY = corners.maxOf { it.y }
            val minZ = corners.minOf { it.z }
            val maxZ = corners.maxOf { it.z }

            // Вырожденные кубы (нулевой размер по любой оси) в модели предмета
            // запрещены — расширяем их до минимально допустимого размера.
            val from = doubleArrayOf(minX, minY, minZ)
            val to = doubleArrayOf(
                if (abs(maxX - minX) < EPSILON) minX + EPSILON else maxX,
                if (abs(maxY - minY) < EPSILON) minY + EPSILON else maxY,
                if (abs(maxZ - minZ) < EPSILON) minZ + EPSILON else maxZ,
            )

            result += BakedCube(
                name = cube.name,
                from = from,
                to = to,
                faces = mapFaces(cube, flipY),
                textureIndex = cube.textureIndex,
            )
        }

        return result
    }

    /**
     * Вычисляет пивот «по умолчанию»: центр по X/Z и верх по Y.
     *
     * Учитываются только исходные координаты кубов — без трансформаций костей,
     * потому что для косметики важна общая симметрия, а не точные габариты.
     */
    fun autoPivot(document: BbDocument): Vec3 {
        if (document.cubes.isEmpty()) return Vec3.ZERO

        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE

        for (cube in document.cubes) {
            minX = minOf(minX, cube.from[0], cube.to[0])
            maxX = maxOf(maxX, cube.from[0], cube.to[0])
            maxY = maxOf(maxY, cube.from[1], cube.to[1])
            minZ = minOf(minZ, cube.from[2], cube.to[2])
            maxZ = maxOf(maxZ, cube.from[2], cube.to[2])
        }

        return Vec3((minX + maxX) / 2.0, maxY, (minZ + maxZ) / 2.0)
    }

    /**
     * Цепочка костей-родителей для куба с именем [cubeName], от ближайшей к корню.
     *
     * Порядок важен: сначала применяется трансформация самой глубокой кости.
     */
    fun groupChain(document: BbDocument, cubeName: String): List<BbGroup> {
        val owner = document.groups.values.firstOrNull { cubeName in it.children } ?: return emptyList()
        val chain = ArrayList<BbGroup>()
        var current: BbGroup? = owner
        var guard = 0
        while (current != null && guard < MAX_GROUP_DEPTH) {
            chain += current
            val parentName = current.parent
            current = if (parentName != null) document.groups[parentName] else null
            guard++
        }
        return chain
    }

    /**
     * Поворот [point] вокруг [origin] на углы [rotation] (градусы, порядок XYZ).
     */
    fun rotateAround(point: Vec3, origin: DoubleArray, rotation: DoubleArray): Vec3 {
        val rx = rotation.getOrElse(0) { 0.0 }
        val ry = rotation.getOrElse(1) { 0.0 }
        val rz = rotation.getOrElse(2) { 0.0 }
        if (abs(rx) < EPSILON && abs(ry) < EPSILON && abs(rz) < EPSILON) return point

        var x = point.x - origin.getOrElse(0) { 0.0 }
        var y = point.y - origin.getOrElse(1) { 0.0 }
        var z = point.z - origin.getOrElse(2) { 0.0 }

        // X
        if (abs(rx) > EPSILON) {
            val rad = Math.toRadians(rx)
            val c = cos(rad)
            val s = sin(rad)
            val ny = y * c - z * s
            val nz = y * s + z * c
            y = ny
            z = nz
        }
        // Y
        if (abs(ry) > EPSILON) {
            val rad = Math.toRadians(ry)
            val c = cos(rad)
            val s = sin(rad)
            val nx = x * c + z * s
            val nz = -x * s + z * c
            x = nx
            z = nz
        }
        // Z
        if (abs(rz) > EPSILON) {
            val rad = Math.toRadians(rz)
            val c = cos(rad)
            val s = sin(rad)
            val nx = x * c - y * s
            val ny = x * s + y * c
            x = nx
            y = ny
        }

        return Vec3(
            x + origin.getOrElse(0) { 0.0 },
            y + origin.getOrElse(1) { 0.0 },
            z + origin.getOrElse(2) { 0.0 },
        )
    }

    /**
     * Переназначает грани и UV при инверсии оси Y.
     *
     * После отражения по Y:
     *  - `up` и `down` меняются местами, а UV переворачивается по V;
     *  - `north`/`south` и `east`/`west` меняются местами, UV переворачивается
     *    по U (иначе текстура окажется зеркальной).
     *
     * При [flipY] = false грани остаются как есть.
     */
    fun mapFaces(cube: BbCube, flipY: Boolean): List<BbFace> {
        if (!flipY) {
            return cube.faces.map { face ->
                if (cube.mirrorUv) face.copy(uv = flipU(face.uv)) else face
            }
        }

        val result = ArrayList<BbFace>(cube.faces.size)
        for (face in cube.faces) {
            val mapped = when (face.face) {
                "up" -> "down" to (false to true)
                "down" -> "up" to (false to true)
                "north" -> "south" to (true to false)
                "south" -> "north" to (true to false)
                "east" -> "west" to (true to true)
                "west" -> "east" to (true to true)
                else -> face.face to (false to false)
            }
            val name = mapped.first
            var uv = face.uv
            if (mapped.second.first || cube.mirrorUv) uv = flipU(uv)
            if (mapped.second.second) uv = flipV(uv)

            // Грани с «вырожденной» UV-развёрткой (u1 == u2 или v1 == v2)
            // Minecraft отбрасывает — пропускаем их сразу.
            if (uv.size >= 4 && abs(uv[0] - uv[2]) > EPSILON && abs(uv[1] - uv[3]) > EPSILON) {
                result += BbFace(name, uv)
            }
        }
        return result
    }

    private fun flipU(uv: List<Double>): List<Double> {
        if (uv.size < 4) return uv
        return listOf(uv[2], uv[1], uv[0], uv[3])
    }

    private fun flipV(uv: List<Double>): List<Double> {
        if (uv.size < 4) return uv
        return listOf(uv[0], uv[3], uv[2], uv[1])
    }

    /** Порядок граней, ожидаемый ванильным загрузчиком. */
    fun faceOrder(): List<String> = FACE_ORDER

    private const val EPSILON = 1e-6

    /** Защита от циклов в графе костей. */
    private const val MAX_GROUP_DEPTH = 64
}

/**
 * Сборка JSON-модели предмета Minecraft из «запечённых» кубов.
 *
 * Пишем JSON вручную: Gson в плагин тянуть не нужно, а формат строго
 * определён и генерируется только здесь.
 */
internal object ModelJsonWriter {

    /**
     * @param textureRefs ссылки вида `3DCosmetics:item/foo` для индексов
     *                    текстур; индекс в списке = индекс текстуры в модели.
     */
    fun write(
        cubes: List<BbGeometry.BakedCube>,
        textureRefs: List<String>,
        textureWidth: Int,
        textureHeight: Int,
        credit: String,
    ): String = buildString {
        appendLine("{")
        appendLine("  \"credit\": ${quote(credit)},")
        appendLine("  \"texture_size\": [$textureWidth, $textureHeight],")

        // Блок textures: ключ — "0", "1", … плюс частица из первой текстуры.
        appendLine("  \"textures\": {")
        val validRefs = textureRefs.filter { it.isNotBlank() }
        validRefs.forEachIndexed { index, ref ->
            val comma = if (index < validRefs.size - 1) "," else ""
            appendLine("    \"$index\": ${quote(ref)}$comma")
        }
        if (validRefs.isNotEmpty()) {
            val comma = if (validRefs.size > 1) "," else ""
            // First texture is the particle by default.
            appendLine("    ${comma}\"particle\": ${quote(validRefs.first())}")
        }
        appendLine("  },")

        appendLine("  \"elements\": [")
        cubes.forEachIndexed { cubeIndex, cube ->
            appendLine("    {")
            appendLine("      \"name\": ${quote(cube.name)},")
            appendLine("      \"from\": [${num(cube.from[0])}, ${num(cube.from[1])}, ${num(cube.from[2])}],")
            appendLine("      \"to\": [${num(cube.to[0])}, ${num(cube.to[1])}, ${num(cube.to[2])}],")
            appendLine("      \"faces\": {")
            cube.faces.forEachIndexed { faceIndex, face ->
                val comma = if (faceIndex < cube.faces.size - 1) "," else ""
                val uv = face.uv
                appendLine(
                    "        \"${face.face}\": {" +
                        "\"uv\": [${num(uv[0])}, ${num(uv[1])}, ${num(uv[2])}, ${num(uv[3])}], " +
                        "\"texture\": \"#${faceTexIndex(face, cube)}\"}$comma"
                )
            }
            appendLine("      }")
            val cubeComma = if (cubeIndex < cubes.size - 1) "," else ""
            appendLine("    }$cubeComma")
        }
        appendLine("  ],")

        // ItemDisplay рендерит предмет в режиме `fixed`, поэтому там модель
        // нужна в человеческом масштабе и без сдвигов.
        appendLine("  \"display\": {")
        appendLine("    \"fixed\": {")
        appendLine("      \"rotation\": [0, 0, 0],")
        appendLine("      \"translation\": [0, 0, 0],")
        appendLine("      \"scale\": [1, 1, 1]")
        appendLine("    }")
        appendLine("  }")
        appendLine("}")
    }

    /** У [BbFace] нет собственного индекса текстуры — берём из куба. */
    private fun faceTexIndex(face: BbFace, cube: BbGeometry.BakedCube): Int = cube.textureIndex

    /** Форматирует число без хвостовых нулей, как это делает Blockbench. */
    fun num(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val rounded = Math.round(value * 1000.0) / 1000.0
        if (rounded == Math.floor(rounded) && abs(rounded) < 1e15) {
            return rounded.toLong().toString()
        }
        return rounded.toString()
    }

    /** Экранирует строку для JSON. */
    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun abs(value: Double): Double = if (value < 0) -value else value
}
