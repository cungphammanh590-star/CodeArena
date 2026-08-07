/**
 * 真正横切、与任何业务域无关的基础设施。
 *
 * <p><b>铁律</b>：只放日志拦截器、统一异常、内网 Token、Redis/Nacos 等平台能力。
 * 与 user/problem 等相关的配置或逻辑必须下沉到对应域，禁止把 shared 当垃圾回收站。
 */
package com.codearena.business.shared;
