<script setup lang="ts">
import { computed, ref } from "vue";

const stages = [
  {
    eyebrow: "今天的任务",
    title: "先复盘滑动窗口",
    text: "你上周在边界条件上连续出错两次。先用 15 分钟理清窗口收缩的时机。",
    action: "开始复盘",
    meta: "来自你的练习记录",
  },
  {
    eyebrow: "Nex 陪练",
    title: "不要急着看答案",
    text: "如果右指针移动后总和仍偏大，哪一个条件决定左指针可以收缩？",
    action: "继续推理",
    meta: "只给当前需要的提示",
  },
  {
    eyebrow: "间隔复习",
    title: "两天后再确认一次",
    text: "掌握不等于做过。系统会把这次卡住的点放回合适的复习节奏。",
    action: "完成本轮",
    meta: "让知识留下来",
  },
];

const active = ref(0);
const current = computed(() => stages[active.value]);
function choose(index: number) {
  active.value = index;
}
</script>

<template>
  <main class="landing-page">
    <header class="landing-nav">
      <RouterLink class="landing-brand" to="/">CodeArena</RouterLink>
      <nav aria-label="首页导航">
        <a href="#how">如何学习</a>
        <RouterLink to="/demo">体验演示</RouterLink>
      </nav>
      <div class="landing-actions">
        <RouterLink class="nav-login" to="/login">登录</RouterLink>
        <RouterLink class="btn-primary" to="/login?mode=register">开始学习</RouterLink>
      </div>
    </header>

    <section class="landing-hero">
      <div class="hero-copy">
        <p class="eyebrow">CS 代码与知识的终身学习空间</p>
        <h1>把每一次练习，变成下一次能用上的能力。</h1>
        <p class="lead">
          CodeArena 连接你的代码练习、知识笔记与复习节奏。Nex 记得你卡在哪里，帮你在需要时想清楚，而不是替你作答。
        </p>
        <div class="hero-actions">
          <RouterLink class="btn-primary" to="/login?mode=register">创建学习空间</RouterLink>
          <RouterLink class="btn-secondary" to="/demo">先看演示</RouterLink>
        </div>
        <p class="fine-print">平台托管学习服务；模型可选用你自己的 API Key。</p>
      </div>

      <section class="demo-card" aria-label="学习流程演示">
        <div class="demo-card-head">
          <span>演示学习空间</span>
          <span class="demo-status">第 {{ active + 1 }} 步 / 3</span>
        </div>
        <div class="demo-body">
          <p class="demo-eyebrow">{{ current.eyebrow }}</p>
          <h2>{{ current.title }}</h2>
          <p>{{ current.text }}</p>
          <p class="demo-meta">{{ current.meta }}</p>
          <button type="button" class="btn-primary" @click="choose((active + 1) % stages.length)">
            {{ current.action }}
          </button>
        </div>
        <div class="demo-steps" aria-label="选择演示步骤">
          <button
            v-for="(stage, index) in stages"
            :key="stage.eyebrow"
            type="button"
            :class="{ active: active === index }"
            @click="choose(index)"
          >
            {{ index + 1 }}
          </button>
        </div>
      </section>
    </section>

    <section id="how" class="value-section">
      <div class="section-intro">
        <p class="eyebrow">不是另一个题库</p>
        <h2>围绕你的学习历史，持续组织下一步。</h2>
      </div>
      <div class="value-grid">
        <article>
          <span>01</span>
          <h3>保存真实过程</h3>
          <p>练习记录、提交和笔记不是一次性数据，而是个人能力档案的起点。</p>
        </article>
        <article>
          <span>02</span>
          <h3>在卡住时陪练</h3>
          <p>Nex 根据题目、历史错误和你的知识库给出恰当提示，保留思考空间。</p>
        </article>
        <article>
          <span>03</span>
          <h3>在遗忘前复习</h3>
          <p>题目和知识点进入间隔复习，让掌握从“做过”变成“能再次用出来”。</p>
        </article>
      </div>
    </section>

    <section class="trust-section">
      <div>
        <p class="eyebrow">你的数据，你的模型选择</p>
        <h2>学习系统与模型调用分开。</h2>
      </div>
      <p>
        CodeArena 提供账号、学习数据、同步、计划与复习服务。需要 AI 陪练时，你可以填写自己的模型服务 Key；不配置 Key 也能管理学习内容与复习队列。
      </p>
    </section>
  </main>
</template>

<style scoped>
.landing-page { min-height: 100vh; background: var(--bg-elevated); }
.landing-nav { max-width: 1120px; margin: 0 auto; min-height: 72px; padding: 0 24px; display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.landing-brand { color: var(--ink); font-size: 19px; font-weight: 700; letter-spacing: -.04em; text-decoration: none; }
.landing-nav nav { display: flex; align-items: center; gap: 20px; margin-left: auto; }
.landing-nav nav a, .nav-login { color: var(--muted); font-size: 14px; text-decoration: none; }
.landing-nav nav a:hover, .nav-login:hover { color: var(--accent); text-decoration: none; }
.landing-actions, .hero-actions { display: flex; align-items: center; gap: 12px; }
.landing-nav :deep(.btn-primary), .hero-actions :deep(.btn-primary), .hero-actions :deep(.btn-secondary), .demo-body .btn-primary { display: inline-flex; align-items: center; justify-content: center; text-decoration: none; }
.landing-hero { max-width: 1120px; margin: 0 auto; padding: 72px 24px 88px; display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(320px, .9fr); gap: 56px; align-items: center; }
.eyebrow, .demo-eyebrow { margin: 0 0 12px; color: var(--accent); font-size: 13px; font-weight: 650; letter-spacing: .04em; }
.hero-copy h1 { max-width: 670px; margin: 0; font-size: clamp(38px, 5vw, 62px); line-height: 1.08; letter-spacing: -.055em; }
.lead { max-width: 600px; margin: 24px 0; color: var(--muted); font-size: 18px; line-height: 1.7; }
.fine-print { margin: 14px 0 0; color: var(--muted); font-size: 13px; }
.demo-card { border: 1px solid var(--line); border-radius: 16px; overflow: hidden; background: var(--card); box-shadow: var(--shadow); }
.demo-card-head { display: flex; justify-content: space-between; gap: 16px; padding: 14px 18px; border-bottom: 1px solid var(--line); color: var(--ink); font-size: 14px; font-weight: 600; }
.demo-status, .demo-meta { color: var(--muted); font-size: 12px; font-weight: 400; }
.demo-body { min-height: 270px; padding: 28px; }
.demo-body h2 { margin: 0; font-size: 27px; letter-spacing: -.035em; }
.demo-body > p:not(.demo-eyebrow):not(.demo-meta) { min-height: 72px; color: var(--muted); line-height: 1.65; }
.demo-meta { margin: 24px 0 10px; }
.demo-steps { display: flex; gap: 8px; padding: 14px 18px; background: var(--soft); }
.demo-steps button { width: 32px; height: 32px; border: 1px solid var(--line-strong); border-radius: 50%; background: var(--card); color: var(--muted); cursor: pointer; }
.demo-steps button.active { color: #fff; border-color: var(--accent); background: var(--accent); }
.value-section, .trust-section { max-width: 1120px; margin: 0 auto; padding: 72px 24px; }
.value-section { border-top: 1px solid var(--line); }
.section-intro { max-width: 620px; }
.section-intro h2, .trust-section h2 { margin: 0; font-size: clamp(28px, 3.5vw, 42px); line-height: 1.18; letter-spacing: -.04em; }
.value-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-top: 32px; }
.value-grid article { padding: 24px; border: 1px solid var(--line); border-radius: var(--radius); background: var(--card); }
.value-grid span { color: var(--accent); font-size: 13px; font-weight: 650; }
.value-grid h3 { margin: 32px 0 8px; font-size: 18px; }
.value-grid p, .trust-section > p { margin: 0; color: var(--muted); line-height: 1.65; }
.trust-section { display: grid; grid-template-columns: 1fr 1fr; gap: 64px; align-items: start; padding-bottom: 96px; }
.trust-section > p { font-size: 16px; }
@media (max-width: 720px) { .landing-nav { padding: 0 16px; } .landing-nav nav { display: none; } .landing-actions { gap: 8px; } .landing-hero { grid-template-columns: 1fr; padding: 48px 16px 64px; gap: 36px; } .lead { font-size: 16px; } .value-section, .trust-section { padding: 48px 16px; } .value-grid, .trust-section { grid-template-columns: 1fr; gap: 16px; } .value-grid article { padding: 20px; } .landing-nav :deep(.btn-primary) { min-height: 40px; padding: 8px 12px; font-size: 13px; } }
</style>
