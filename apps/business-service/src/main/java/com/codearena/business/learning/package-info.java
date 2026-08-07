/**
 * 学习域（模块化单体内部再分子域，避免巨石）。
 *
 * <ul>
 *   <li>{@code preference} — 学习偏好</li>
 *   <li>{@code list} — 题单</li>
 *   <li>{@code mastery} — 掌握标记</li>
 *   <li>{@code plan} — 复习计划 / 今日队列</li>
 * </ul>
 *
 * <p>子域之间应通过本域内门面或对方子包的明确 API 交互，禁止随意直穿 Repository。
 */
package com.codearena.business.learning;
