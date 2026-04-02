package morph.runtime

sealed class MorphValue

data class IntValue(val v: Long) : MorphValue()
data class FloatValue(val v: Double) : MorphValue()
data class StringValue(val v: String) : MorphValue()
data class BoolValue(val v: Boolean) : MorphValue()
data class UuidValue(val v: String) : MorphValue()
data class ProductValue(val typeName: String, val fields: Map<String, MorphValue>) : MorphValue()
data class VariantValue(val typeName: String, val variantName: String, val fields: List<MorphValue>) : MorphValue()
data class ListValue(val items: List<MorphValue>) : MorphValue()
data class SetValue(val items: Set<MorphValue>) : MorphValue()
data class MapValue(val entries: Map<MorphValue, MorphValue>) : MorphValue()
data object UnitValue : MorphValue()
