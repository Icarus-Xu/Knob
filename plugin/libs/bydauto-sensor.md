# 传感器类

## 1.方法概要
类 **BYDAutoSensorDevice**
`public class BYDAutoSensorDevice`

|限定符和返回类型|方法|描述|
|----|----|----|
|`static BYDAutoSensorDevice`|`getInstance(Context con)`|获取实例|
|`int`|`getLightIntensity()`|获取光照( 环境光) 强度等级|
|`void`|`registerListener(AbsBYDAutoSensorListener l)`|监听注册接口|
|`void`|`unregisterListener(AbsBYDAutoSensorListener l)`|取消注册接口|

## 2.获取光照强度等级
**API 名称**：`int getLightIntensity()`

**接口描述**：获取光照( 环境光) 强度等级

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|等级|LIGHT_ILLUM_LEVEL1 大于 230Lux|
|int|等级|LIGHT_ILLUM_LEVEL2 大于 180Lux ，小230Lux|
|int|等级|LIGHT_ILLUM_LEVEL3 大于 130Lux ，小180Lux|
|int|等级|LIGHT_ILLUM_LEVEL4 大于 80Lux ，小130Lux|
|int|等级|LIGHT_ILLUM_LEVEL5 小于 80Lux|

## 3.监听方法
类 **AbsBYDAutoSensorListener**
`public abstract class AbsBYDAutoSensorListener`

### 方法概要
|限定符和返回类型|方法|描述|输入|
|----|----|----|----|
|`void`|`onLightIntensityChanged(int value)`|监听光照强度变化|光照等级|

> 备注：输入值的取值范围参见 get 函数的具体描述。
