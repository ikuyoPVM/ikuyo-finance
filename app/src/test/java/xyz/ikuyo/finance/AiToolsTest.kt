package xyz.ikuyo.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.ikuyo.finance.core.Json
import xyz.ikuyo.finance.core.JsonValue
import xyz.ikuyo.finance.data.ai.AiToolSpec
import xyz.ikuyo.finance.data.ai.AiTools

/**
 * 工具定义的结构测试：只校验 schema 的形状与自洽，不触碰仓储。
 * 真机联调见维护迭代 01 的验收记录。
 */
class AiToolsTest {

    @Test
    fun `十一个工具的名字唯一且与常量一致`() {
        val names = AiTools.ALL.map { it.name }
        assertEquals(11, names.size)
        assertEquals(names.size, names.toSet().size)
        assertEquals(AiTools.NAMES, names.toSet())
    }

    @Test
    fun `每个工具都有可读的描述`() {
        AiTools.ALL.forEach { spec ->
            assertTrue("${spec.name} 缺少描述", spec.description.length >= 8)
        }
    }

    @Test
    fun `参数都是 object 且 required 落在 properties 里`() {
        AiTools.ALL.forEach { spec ->
            val root = spec.parameters as? JsonValue.Obj ?: error("${spec.name} 参数不是对象")
            assertEquals("object", root.str("type"))
            val properties = root.obj("properties") ?: error("${spec.name} 缺少 properties")
            val required = root.arr("required")?.items.orEmpty()
            required.forEach { item ->
                val key = (item as? JsonValue.Str)?.value ?: error("${spec.name} required 不是字符串")
                assertNotNull("${spec.name} 的 required 项 $key 不在 properties 里", properties.fields[key])
            }
        }
    }

    @Test
    fun `时间范围参数与统一枚举保持一致`() {
        AiTools.ALL.forEach { spec ->
            val properties = (spec.parameters as? JsonValue.Obj)?.obj("properties") ?: return@forEach
            val range = properties.obj("range") ?: return@forEach
            val values = range.arr("enum")?.items.orEmpty().mapNotNull { (it as? JsonValue.Str)?.value }
            assertEquals("${spec.name} 的 range 枚举不一致", AiTools.RANGES, values)
        }
    }

    @Test
    fun `导出的工具定义可以被重新解析`() {
        val dumped = Json.dumps(AiTools.schemasJson())
        val parsed = Json.parseOrNull(dumped) as? JsonValue.Arr ?: error("工具定义不是数组")
        assertEquals(AiTools.ALL.size, parsed.items.size)
        parsed.items.forEach { item ->
            val tool = item as? JsonValue.Obj ?: error("工具项不是对象")
            assertEquals("function", tool.str("type"))
            val function = tool.obj("function") ?: error("缺少 function")
            assertTrue(AiTools.NAMES.contains(function.str("name")))
            assertNotNull(function.obj("parameters"))
        }
    }

    @Test
    fun `单项定义包含完整的 function 包装`() {
        val spec = AiToolSpec(
            name = AiTools.GET_ACCOUNT_SUMMARY,
            description = "读取某个时间范围内的收支摘要",
            parameters = AiTools.ALL.first { it.name == AiTools.GET_ACCOUNT_SUMMARY }.parameters,
        )
        val json = spec.toJson() as? JsonValue.Obj ?: error("toJson 不是对象")
        assertEquals("function", json.str("type"))
        assertEquals(AiTools.GET_ACCOUNT_SUMMARY, json.obj("function")?.str("name"))
    }
}