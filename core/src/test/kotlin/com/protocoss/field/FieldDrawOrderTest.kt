package com.protocoss.field

import com.badlogic.gdx.utils.JsonReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FieldDrawOrderTest {
    @Test fun `order matches original executable including ties and offscreen actors`() {
        val reference=JsonReader().parse(Files.readString(Path.of("../docs/field-draw-order-native.json")))
        for(case in reference.get("cases")) {
            assertEquals(case.get("order").asIntArray().toList(),
                FieldDrawOrder.indices(case.get("y").map { it.asFloat() }),case.toString())
        }
    }
}
