# 空调类

## 1.方法概要
类 **BYDAutoAcDevice**
`public class BYDAutoAcDevice`

|限定符和返回类型|方法|描述|
|----|----|----|
|`static BYDAutoAcDevice`|`getInstance(Context con)`|获取实例|
|`int`|`getAcCompressorMode()`|获取 A/C ( 压缩机) 状态|
|`int`|`getAcCompressorManualSign()`|获取 A/C ( 压缩机) 手动标志|
|`int`|`getAcWindLevelManualSign()`|获取风量手动标志|
|`int`|`getAcWindModeManualSign()`|获取出风模式手动标志|
|`int`|`getAcStartState()`|获取空调开启状态|
|`int`|`getAcControlMode()`|获取空调控制方式|
|`int`|`getAcCycleMode()`|获取空调循环方式|
|`int`|`getAcWindMode()`|获取空调出风模式|
|`int`|`getAcDefrostState(int area)`|获取空调除霜状态|
|`int`|`getAcWindLevel()`|获取空调风量档位|
|`int`|`getTemprature(int area)`|获取空调各区域温度|
|`int`|`getTemperatureUnit()`|获取默认温度单位|
|`int`|`getAcTemperatureControlMode()`|获取主副驾驶温度分控方式|
|`int`|`getAcVentilationState()`|获取通风功能设置|
|`int`|`getRearAcStartState()`|获取后空调开启状态|
|`int`|`setAcControlMode(int setSource,int mode)`|设置空调控制方式|
|`int`|`setAcCycleMode(int setSource,int mode)`|设置空调循环方式|
|`int`|`setAcWindMode(int setSource,int mode)`|设置空调出风模式|
|`int`|`setAcDefrostState(int setSource,int area,int state)`|设置前后排空调除霜状态|
|`int`|`setAcWindLevel(int setSource ,int level)`|设置空调风量档位|
|`int`|`setAcTemperature(int type,int value,int tempSource, int unit)`|设置空调温度|
|`int`|`setAcTemperatureControlMode(int setSource,int mode)`|设置主副驾驶温度分控方式|
|`int`|`setAcVentilationState(int setSource,int state)`|设置驻车通风功能|
|`int`|`start(int setSource)`|开启空调|
|`int`|`startRearAc(int setSource)`|开启后排空调|
|`int`|`stop(int setSource)`|关闭空调|
|`int`|`stopRearAc(int setSource)`|关闭后排空调|
|`void`|`registerListener(AbsBYDAutoAcListener l)`|监听注册接口|
|`void`|`unregisterListener(AbsBYDAutoAcListener l)`|取消注册接口|

## 2.获取 A/C (压缩机)状态
**API 名称**：`int getAcCompressorMode()`

**接口描述**：获取 A/C (压缩机)状态

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|A/C (压缩机) 状态|AC_COMPRESSOR_OFF 压缩机关|
|int|A/C (压缩机) 状态|AC_COMPRESSOR_ON 压缩机开|

## 3.获取 A/C (压缩机)手动标志
**API 名称**：`int getAcCompressorManualSign()`

**接口描述**：获取 A/C (压缩机)手动标志

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|A/C (压缩机) 手动标志|AC_COMPRESSOR_MANUAL_SIGN_OFF 自动控制|
|int|A/C (压缩机) 手动标志|AC_COMPRESSOR_MANUAL_SIGN_ON 手动控制|

## 4.获取风量手动标志
**API 名称**：`int getAcWindLevelManualSign()`

**接口描述**：获取风量手动标志

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|风量手动标志|AC_WINDLEVEL_MANUAL_SIGN_OFF 自动控制|
|int|风量手动标志|AC_WINDLEVEL_MANUAL_SIGN_ON 手动控制|

## 5.获取出风模式手动标志
**API 名称**：`int getAcWindModeManualSign()`

**接口描述**：获取出风模式手动标志

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|出风模式手动标志|AC_WINDMODE_MANUAL_SIGN_OFF 自动控制|
|int|出风模式手动标志|AC_WINDMODE_MANUAL_SIGN_ON 手动控制|

## 6.获取空调开启状态
**API 名称**：`int getAcStartState()`

**接口描述**：获取空调开启状态

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|空调开启状态|AC_POWER_ON 开启|
|int|空调开启状态|AC_POWER_OFF 关闭|

## 7.获取空调控制方式
**API 名称**：`int getAcControlMode()`

**接口描述**：获取空调控制方式

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|空调控制方式|AC_CTRLMODE_MANUAL 手动|
|int|空调控制方式|AC_CTRLMODE_AUTO 自动|

## 8.获取空调循环方式
**API 名称**：`int getAcCycleMode()`

**接口描述**：获取空调循环方式

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|空调循环方式|AC_CYCLEMODE_OUTLOOP 外循环|
|int|空调循环方式|AC_CYCLEMODE_INLOOP 内循环|

## 9.获取空调出风模式
**API 名称**：`int getAcWindMode()`

**接口描述**：获取空调出风模式

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|空调出风模式|AC_WINDMODE_FACE 1：吹面|
|int|空调出风模式|AC_WINDMODE_FACEFOOT 2：吹面吹脚|
|int|空调出风模式|AC_WINDMODE_FOOT 3：吹脚|
|int|空调出风模式|AC_WINDMODE_FOOTDEFROST 4：吹脚除霜|
|int|空调出风模式|AC_WINDMODE_DEFROST 5：除霜|
|int|空调出风模式|AC_WINDMODE_FACEFOOTDEFROST 6：吹面+吹脚+除霜|
|int|空调出风模式|AC_WINDMODE_FACEDEFROST 7：吹面+除霜|

## 10.获取空调除霜状态
**API 名称**：`int getAcDefrostState(int area)`

**接口描述**：获取空调除霜状态

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|area|除霜开关位置 AC_DEFROST_AREA_FRONT 前除霜开关|
|int|area|除霜开关位置 AC_DEFROST_AREA_REAR 后除霜开关|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|所选中的除霜开关的状态|AC_DEFROST_STATE_OFF 除霜状态关闭|
|int|所选中的除霜开关的状态|AC_DEFROST_STATE_ON 除霜状态开启|

> 备注：后除霜仅在有后除霜功能配置的车型上适用

## 11.获取空调风量档位
**API 名称**：`int getAcWindLevel()`

**接口描述**：获取风量档位

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|风量档位|AC_WINDLEVEL_0 0级风量|
|int|风量档位|AC_WINDLEVEL_1 1级风量|
|int|风量档位|AC_WINDLEVEL_2 2级风量|
|int|风量档位|AC_WINDLEVEL_3 3级风量|
|int|风量档位|AC_WINDLEVEL_4 4级风量|
|int|风量档位|AC_WINDLEVEL_5 5级风量|
|int|风量档位|AC_WINDLEVEL_6 6级风量|
|int|风量档位|AC_WINDLEVEL_7 7级风量|

## 12.获取空调各区域温度
**API 名称**：`int getTemprature(int area)`

**接口描述**：获取空调各区域温度

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|area|区域 AC_TEMPERATURE_MAIN 主驾驶温度|
|int|area|区域 AC_TEMPERATURE_DEPUTY 副驾驶温度|
|int|area|区域 AC_TEMPERATURE_REAR 后空调|
|int|area|区域 AC_TEMPERATURE_OUT 车外温度|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|空调各区域温度|[AC_TEMP_IN_CELSIUS_MIN,AC_TEMP_IN_CELSIUS_MAX]℃; [AC_TEMP_IN_FAHRENHEIT_MIN,AC_TEMP_IN_FAHRENHEIT_MAX]℉<br>车内主副驾温度：<br>1 )当温度单位为摄氏温度℃时(默认)：范围：[17，33]；<br>2 )当温度单位为华氏温度℉时：范围[64，91]。|
|int|空调各区域温度|[AC_TEMP_OUT_CELSIUS_MIN,AC_TEMP_OUT_CELSIUS_MAX]℃; [AC_TEMP_OUT_FAHRENHEIT_MIN,AC_TEMP_OUT_FAHRENHEIT_MAX] ℉<br>车外温度：<br>1 )摄氏单位:温度范围[-40℃， 50℃]；<br>2 )华氏单位: [-40℉， 122℉]。|

## 13.获取默认温度单位
**API 名称**：`int getTemperatureUnit()`

**接口描述**：获取默认温度单位

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|默认温度单位|AC_TEMPERATURE_UNIT_OF 华氏℉|
|int|默认温度单位|AC_TEMPERATURE_UNIT_OC 摄氏℃|

## 14.获取主副驾驶温度分控方式
**API 名称**：`int getAcTemperatureControlMode()`

**接口描述**：获取主副驾驶温度分控方式

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|主副驾驶温度分控方式|AC_TEMPCTRL_SEPARATE_OFF 不分控|
|int|主副驾驶温度分控方式|AC_TEMPCTRL_SEPARATE_ON 分控|

## 15.获取通风功能设置
**API 名称**：`int getAcVentilationState()`

**接口描述**：获取通风功能设置

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|通风功能设置|AC_VENTILATION_STATE_ON 打开|
|int|通风功能设置|AC_VENTILATION_STATE_OFF 关闭|

## 16.获取后空调开启状态
**API 名称**：`int getRearAcStartState()`

**接口描述**：获取后空调开启状态

**输入参数**：无

|返回值数据类型|名称|值描述|
|----|----|----|
|int|后空调开启状态|AC_POWER_ON 开启|
|int|后空调开启状态|AC_POWER_OFF 关闭|

> 备注：适用于有后排空调配置的车型

## 17.设置空调控制方式
**API 名称**：`int setAcControlMode(int setSource,int mode)`

**接口描述**：设置空调控制方式

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|setSource|控制来源 AC_CTRL_SOURCE_VOICE 声控|
|int|setSource|控制来源 AC_CTRL_SOURCE_UI_KEY 触屏/按键|
|int|mode|空调控制方式 AC_CTRLMODE_MANUAL 手动|
|int|mode|空调控制方式 AC_CTRLMODE_AUTO 自动|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|设置状态后的结果|AC_COMMAND_SUCCESS 成功|
|int|设置状态后的结果|AC_COMMAND_INVALID_VALUE 无效值|
|int|设置状态后的结果|AC_COMMAND_TIMEOUT 超时|
|int|设置状态后的结果|AC_COMMAND_BUSY 忙碌|
|int|设置状态后的结果|AC_COMMAND_FAILED 失败|

## 18.设置空调循环方式
**API 名称**：`int setAcCycleMode(int setSource,int mode)`

**接口描述**：设置空调循环方式

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|setSource|控制来源 AC_CTRL_SOURCE_VOICE 声控|
|int|setSource|控制来源 AC_CTRL_SOURCE_UI_KEY 触屏/按键|
|int|mode|空调循环方式 AC_CYCLEMODE_INLOOP 内循环|
|int|mode|空调循环方式 AC_CYCLEMODE_OUTLOOP 外循环|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|设置状态后的结果|AC_COMMAND_SUCCESS 成功|
|int|设置状态后的结果|AC_COMMAND_INVALID_VALUE 无效值|
|int|设置状态后的结果|AC_COMMAND_TIMEOUT 超时|
|int|设置状态后的结果|AC_COMMAND_BUSY 忙碌|
|int|设置状态后的结果|AC_COMMAND_FAILED 失败|

## 19.设置空调出风模式
**API 名称**：`int setAcWindMode(int setSource,int mode)`

**接口描述**：设置空调出风模式

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|setSource|控制来源 AC_CTRL_SOURCE_VOICE 声控|
|int|setSource|控制来源 AC_CTRL_SOURCE_UI_KEY 触屏/按键|
|int|mode|模式 AC_WINDMODE_FACE 1：吹面|
|int|mode|模式 AC_WINDMODE_FACEFOOT 2：吹面吹脚|
|int|mode|模式 AC_WINDMODE_FOOT 3：吹脚|
|int|mode|模式 AC_WINDMODE_FOOTDEFROST 4：吹脚除霜|
|int|mode|模式 AC_WINDMODE_DEFROST 5：除霜|
|int|mode|模式 AC_WINDMODE_FACEFOOTDEFROST 6：吹面+吹脚+除霜|
|int|mode|模式 AC_WINDMODE_FACEDEFROST 7：吹面+除霜|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|设置状态后的结果|AC_COMMAND_SUCCESS 成功|
|int|设置状态后的结果|AC_COMMAND_INVALID_VALUE 无效值|
|int|设置状态后的结果|AC_COMMAND_TIMEOUT 超时|
|int|设置状态后的结果|AC_COMMAND_BUSY 忙碌|
|int|设置状态后的结果|AC_COMMAND_FAILED 失败|

## 20.设置前后排空调除霜状态
**API 名称**：`int setAcDefrostState(int setSource,int area, int state)`

**接口描述**：设置前后排空调除霜状态

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|setSource|控制来源 AC_CTRL_SOURCE_VOICE 声控|
|int|setSource|控制来源 AC_CTRL_SOURCE_UI_KEY 触屏/按键|
|int|area|区域 AC_DEFROST_AREA_FRONT 前除霜开关|
|int|area|区域 AC_DEFROST_AREA_REAR 后除霜开关|
|int|state|状态 AC_DEFROST_STATE_ON 除霜状态开启|
|int|state|状态 AC_DEFROST_STATE_OFF 除霜状态关闭|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|设置状态后的结果|AC_COMMAND_SUCCESS 成功|
|int|设置状态后的结果|AC_COMMAND_INVALID_VALUE 无效值|
|int|设置状态后的结果|AC_COMMAND_TIMEOUT 超时|
|int|设置状态后的结果|AC_COMMAND_BUSY 忙碌|
|int|设置状态后的结果|AC_COMMAND_FAILED 失败|

> 备注：后除霜仅在有后除霜功能配置的车型上适用

## 21.设置空调风量档位
**API 名称**：`int setAcWindLevel(int setSource ,int level)`

**接口描述**：设置空调风量档位

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|setSource|控制来源 AC_CTRL_SOURCE_VOICE 声控|
|int|setSource|控制来源 AC_CTRL_SOURCE_UI_KEY 触屏/按键|
|int|level|风量档位 AC_WINDLEVEL_1 1级风量|
|int|level|风量档位 AC_WINDLEVEL_2 2级风量|
|int|level|风量档位 AC_WINDLEVEL_3 3级风量|
|int|level|风量档位 AC_WINDLEVEL_4 4级风量|
|int|level|风量档位 AC_WINDLEVEL_5 5级风量|
|int|level|风量档位 AC_WINDLEVEL_6 6级风量|
|int|level|风量档位 AC_WINDLEVEL_7 7级风量|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|设置状态后的结果|AC_COMMAND_SUCCESS 成功|
|int|设置状态后的结果|AC_COMMAND_INVALID_VALUE 无效值|
|int|设置状态后的结果|AC_COMMAND_TIMEOUT 超时|
|int|设置状态后的结果|AC_COMMAND_BUSY 忙碌|
|int|设置状态后的结果|AC_COMMAND_FAILED 失败|

## 22.设置空调温度
**API 名称**：`int setAcTemperature(int type,int value,int tempSource,int unit)`

**接口描述**：设置空调温度

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|type|空调位置 AC_TEMPERATURE_MAIN 主驾驶空调|
|int|type|空调位置 AC_TEMPERATURE_DEPUTY 副驾驶空调|
|int|type|空调位置 AC_TEMPERATURE_MAIN_DEPUTY 主副驾 (不分控时有效)|
|int|type|空调位置 AC_TEMPERATURE_REAR 后空调|
|int|value|温度值[AC_TEMP_IN_FAHRENHEIT_MIN, AC_TEMP_IN_FAHRENHEIT_MAX]℉或[AC_TEMP_IN_CELSIUS_MIN,AC_TEMP_IN_CELSIUS_MAX]℃<br>华氏温度范围[64，91]摄氏温度范围[17，33]|
|int|tempSource|控制途径 AC_CTRL_SOURCE_UI_KEY 触屏/按键|
|int|tempSource|控制途径 AC_CTRL_SOURCE_VOICE 语音控制|
|int|unit|温度单位 AC_TEMPERATURE_UNIT_OF 温度单位：华氏温度|
|int|unit|温度单位 AC_TEMPERATURE_UNIT_OC 温度单位：摄氏度|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|设置状态后的结果|AC_COMMAND_SUCCESS 成功|
|int|设置状态后的结果|AC_COMMAND_INVALID_VALUE 无效值|
|int|设置状态后的结果|AC_COMMAND_TIMEOUT 超时|
|int|设置状态后的结果|AC_COMMAND_BUSY 忙碌|
|int|设置状态后的结果|AC_COMMAND_FAILED 失败|

## 23.设置主副驾驶温度分控方式
**API 名称**：`int setAcTemperatureControlMode(int setSource,int mode)`

**接口描述**：设置主副驾驶温度分控方式

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|setSource|控制来源 AC_CTRL_SOURCE_VOICE 声控|
|int|setSource|控制来源 AC_CTRL_SOURCE_UI_KEY 触屏/按键|
|int|mode|分控方式 AC_TEMPCTRL_SEPARATE_OFF 不分控|
|int|mode|分控方式 AC_TEMPCTRL_SEPARATE_ON 分控|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|设置状态后的结果|AC_COMMAND_SUCCESS 成功|
|int|设置状态后的结果|AC_COMMAND_INVALID_VALUE 无效值|
|int|设置状态后的结果|AC_COMMAND_TIMEOUT 超时|
|int|设置状态后的结果|AC_COMMAND_BUSY 忙碌|
|int|设置状态后的结果|AC_COMMAND_FAILED 失败|

## 24.设置驻车通风功能
**API 名称**：`int setAcVentilationState(int setSource,int state)`

**接口描述**：设置驻车通风功能

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|setSource|控制来源 AC_CTRL_SOURCE_VOICE 声控|
|int|setSource|控制来源 AC_CTRL_SOURCE_UI_KEY 触屏/按键|
|int|state|通风功能 AC_VENTILATION_STATE_ON 打开|
|int|state|通风功能 AC_VENTILATION_STATE_OFF 关闭|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|设置状态后的结果|AC_COMMAND_SUCCESS 成功|
|int|设置状态后的结果|AC_COMMAND_INVALID_VALUE 无效值|
|int|设置状态后的结果|AC_COMMAND_TIMEOUT 超时|
|int|设置状态后的结果|AC_COMMAND_BUSY 忙碌|
|int|设置状态后的结果|AC_COMMAND_FAILED 失败|

## 25.开启空调
**API 名称**：`int start(int setSource)`

**接口描述**：开启空调

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|setSource|控制来源 AC_CTRL_SOURCE_VOICE 声控|
|int|setSource|控制来源 AC_CTRL_SOURCE_UI_KEY 触屏/按键|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|开启空调结果|AC_COMMAND_SUCCESS 成功|
|int|开启空调结果|AC_COMMAND_INVALID_VALUE 无效值|
|int|开启空调结果|AC_COMMAND_TIMEOUT 超时|
|int|开启空调结果|AC_COMMAND_BUSY 忙碌|
|int|开启空调结果|AC_COMMAND_FAILED 失败|

## 26.关闭空调
**API 名称**：`int stop(int setSource)`

**接口描述**：关闭空调

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|setSource|控制来源 AC_CTRL_SOURCE_VOICE 声控|
|int|setSource|控制来源 AC_CTRL_SOURCE_UI_KEY 触屏/按键|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|关闭空调结果|AC_COMMAND_SUCCESS 成功|
|int|关闭空调结果|AC_COMMAND_INVALID_VALUE 无效值|
|int|关闭空调结果|AC_COMMAND_TIMEOUT 超时|
|int|关闭空调结果|AC_COMMAND_BUSY 忙碌|
|int|关闭空调结果|AC_COMMAND_FAILED 失败|

## 27.开启后排空调
**API 名称**：`int startRearAc(int setSource)`

**接口描述**：开启后排空调

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|setSource|控制来源 AC_CTRL_SOURCE_VOICE 声控|
|int|setSource|控制来源 AC_CTRL_SOURCE_UI_KEY 触屏/按键|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|开启后排空调结果|AC_COMMAND_SUCCESS 成功|
|int|开启后排空调结果|AC_COMMAND_INVALID_VALUE 无效值|
|int|开启后排空调结果|AC_COMMAND_TIMEOUT 超时|
|int|开启后排空调结果|AC_COMMAND_BUSY 忙碌|
|int|开启后排空调结果|AC_COMMAND_FAILED 失败|

> 备注：适用于有后排空调配置的车型

## 28.关闭后排空调
**API 名称**：`int stopRearAc(int setSource)`

**接口描述**：关闭后排空调

**输入参数**

|数据类型|字段名称|值描述|
|----|----|----|
|int|setSource|控制来源 AC_CTRL_SOURCE_VOICE 声控|
|int|setSource|控制来源 AC_CTRL_SOURCE_UI_KEY 触屏/按键|

**返回值**

|数据类型|名称|值描述|
|----|----|----|
|int|关闭后排空调结果|AC_COMMAND_SUCCESS 成功|
|int|关闭后排空调结果|AC_COMMAND_INVALID_VALUE 无效值|
|int|关闭后排空调结果|AC_COMMAND_TIMEOUT 超时|
|int|关闭后排空调结果|AC_COMMAND_BUSY 忙碌|
|int|关闭后排空调结果|AC_COMMAND_FAILED 失败|

> 备注：适用于有后排空调配置的车型

## 29.监听方法
类 **AbsBYDAutoAcListener**
`public abstract class AbsBYDAutoAcListener`

### 方法概要
|限定符和返回类型|方法|描述|输入|
|----|----|----|----|
|`void`|`onAcCompressorManualSignChanged(int sign)`|监听 A/C(压缩机)手动标志的变化|手动标志|
|`void`|`onAcCompressorModeChanged(int mode)`|监听 A/C(压缩机)状态的变化|压缩机状态|
|`void`|`onAcCtrlModeChanged(int mode)`|监听控制方式的变化|控制方式|
|`void`|`onAcCycleModeChanged(int mode)`|监听循环方式的变化|循环方式|
|`void`|`onAcDefrostStateChanged(int area,int state)`|监听空调除霜方式的变化|区域 ( 前除霜开关， 后除霜开关)开关的状态|
|`void`|`onAcRearStarted()`|监听后空调开启状态|无|
|`void`|`onAcRearStoped()`|监听后空调关闭状态|无|
|`void`|`onAcStarted()`|监听空调开启状态|无|
|`void`|`onAcStoped()`|监听空调关闭状态|无|
|`void`|`onAcVentilationStateChanged(int state)`|监听通风功能设置的变化|状态|
|`void`|`onAcWindLevelChanged(int level)`|监听风量档位的变化|风量档位|
|`void`|`onAcWindLevelManualSignChanged(int sign)`|监听风量手动标志的变化|手动标志|
|`void`|`onAcWindModeChanged(int mode)`|监听出风模式的变化|出风模式|
|`void`|`onAcWindModeManualSignChanged(int sign)`|监听出风模式手动标志的变化|手动标志|
|`void`|`onAcWindModeShownStateChanged(int state)`|监听空调出风模式显示状态|状态|
|`void`|`onTemperatureChanged(int area,int value)`|监听各区域温度的变化|空调区域温度值|
|`void`|`onTemperatureUnitChanged(int unit)`|监听温度单位的变化|温度单位|

> 备注：输入值的取值范围参见 get 函数的具体描述。
