---
layout: default
title: Home
---

<div class="home-wrap">
  <h1>Drelo 的博客</h1>
  <p class="home-lead">记录、学习和随手写下的东西。文章支持图片和公式；收藏夹在旁边。</p>
  <p class="home-nav">
    <a href="all-posts.html">所有文章</a>
    ·
    <a href="write.html">写文章</a>
    ·
    <a href="new2.html">网址收藏</a>
  </p>

  <ul class="post-list">
    {% for post in site.posts limit:12 %}
      <li>
        <a href="/newpage{{ post.url }}">{{ post.title }}</a>
        <span>{{ post.date | date: "%Y-%m-%d" }}</span>
      </li>
    {% endfor %}
  </ul>
</div>

<style>
  .home-wrap { max-width: 720px; margin: 40px auto 80px; padding: 0 16px; }
  .home-wrap h1 { color: #4a6fa5; }
  .home-lead { color: #667; margin: 8px 0 16px; }
  .home-nav { margin-bottom: 28px; }
  .home-nav a { color: #4a6fa5; }
  .post-list { list-style: none; padding: 0; }
  .post-list li { display: flex; justify-content: space-between; gap: 12px; padding: 10px 0; border-bottom: 1px solid #eee; }
  .post-list a { color: #333; text-decoration: none; font-weight: 600; }
  .post-list a:hover { color: #4a6fa5; }
  .post-list span { color: #888; font-size: 0.9rem; white-space: nowrap; }
</style>
