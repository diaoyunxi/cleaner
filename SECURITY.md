# 安全策略

## 报告安全漏洞

如果你发现了安全漏洞，请通过以下方式报告：

1. **请勿**在公开的 GitHub Issue 中报告安全漏洞
2. 请通过 GitHub 的 [Security Advisories](https://github.com/diaoyunxi/cleaner/security/advisories/new) 页面提交报告

## 安全范围

以下属于本项目的安全关注点：

- 文件删除操作的数据安全（不可恢复删除）
- Storage 权限滥用
- 用户文件信息泄露
- 文件路径穿越（CWE-22）
- 第三方库安全漏洞
