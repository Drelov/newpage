---
layout: default
title: Home
---
<div style="text-align: center; margin-top: 100px; font-family: 'Arial', sans-serif;">

  <h1 style="font-size: 36px; color: #333;">欢迎访问博客</h1>

  <div class="login-container" style="max-width: 400px; margin: 0 auto; padding: 30px; border: 1px solid #ddd; border-radius: 10px; background-color: #f9f9f9; box-shadow: 0 4px 8px rgba(0, 0, 0, 0.1);">

    <form id="login-form" action="/newpage/all-posts.html" method="GET" style="display: flex; flex-direction: column; gap: 20px;">

      <label for="username" style="font-size: 18px; color: #555;">用户名:</label>
      <input type="text" id="username" name="username" required style="padding: 10px; font-size: 16px; border: 1px solid #ccc; border-radius: 5px; outline: none; transition: border-color 0.3s;">
      
      <label for="password" style="font-size: 18px; color: #555;">密码:</label>
      <input type="password" id="password" name="password" required style="padding: 10px; font-size: 16px; border: 1px solid #ccc; border-radius: 5px; outline: none; transition: border-color 0.3s;">
      
      <button type="submit" style="padding: 12px 20px; background-color: #4CAF50; color: white; font-size: 18px; border: none; border-radius: 5px; cursor: pointer; transition: background-color 0.3s;">
        登录
      </button>

    </form>

  </div>

  <p style="margin-top: 20px; color: #888;">没有账户？<a href="#" style="color: #4CAF50; text-decoration: none;">注册</a></p>

</div>

<!-- 登录表单校验脚本 -->
<script>
  document.getElementById('login-form').addEventListener('submit', function(e) {
    const username = document.getElementById('username').value;
    const password = document.getElementById('password').value;
    if (username !== 'admin' || password !== 'password123') { // 修改为你的用户名和密码
      alert('用户名或密码错误');
      e.preventDefault();
    }
  });
</script>

<!-- 美化样式 -->
<style>
  /* 输入框和按钮聚焦时的效果 */
  input:focus, button:focus {
    border-color: #4CAF50;
    outline: none;
  }

  /* 按钮悬停效果 */
  button:hover {
    background-color: #45a049;
  }

  /* 页面背景颜色 */
  body {
    background-color: #f4f4f9;
    margin: 0;
    padding: 0;
    font-family: 'Arial', sans-serif;
  }

  /* 页面标题的字体和样式 */
  h1 {
    color: #333;
    font-size: 36px;
    margin-bottom: 20px;
  }

  /* 提示链接的样式 */
  a {
    text-decoration: underline;
  }

  a:hover {
    color: #45a049;
  }
</style>
