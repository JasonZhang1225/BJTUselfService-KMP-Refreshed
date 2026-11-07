# CITEL 编程作业判题与提交完善

分支：`codex/Duo`。Windows 共享代码修改；iOS 构建、模拟器与真机检查待 Mac 完成。

## 网页核对

通过 Chrome CDP 只读核对当前登录网页：编程作业索引是 `AC: Accepted`；提交页显示 `You have passed this practise.`，不再提供上传表单；结果页显示 6 个用例、通过 6、失败 0。每个用例有 Passed、My Judge Result、耗时与内存列。

课程模块 `view.php?id=...` 与编程活动 `result.php?a=...` 使用不同标识。结果页地址从平台链接读取，不把模块 id 拼成活动 a。本轮没有向实际作业发送代码或提交 POST；测试数据为虚构内容。

## 行为

- 编程作业用 AC 判断通过，不能把普通“已提交”、RJ 或 WA 当作完成。详情与列表显示判题状态，题目分值仍独立显示。
- 进入详情先读取当前判题结果。AC 显示“已通过（AC），无需再次提交”，不再请求或展示上传表单。
- 即使索引滞后，提交页的已通过提示也能识别；此时不会把缺少表单当作普通上传错误。
- 测试中显示进度提示，详情可见且窗口聚焦时每 3 秒刷新；结束后停止。失败时暂停并提示手动刷新。轮询只做结果读取，不重发代码。
- 成功提交的次数已增加但判题字段暂时为空时，继续等待测试，而不是认定未提交或完成。
- 显示通过数／总数、失败数及逐个用例的判题状态、耗时和内存；平台暂未提供的数量不虚构。解析时只读取外层用例行，避免输入输出里的嵌套表格干扰。
- 模型与提交客户端都阻止已通过或正在测试时重复提交。提交次数已确认后，结果详情临时获取失败不会自动再次 POST。

## 验证

运行 `:shared:desktopTest`，筛选 `CitelTest`、`CitelProgrammingSubmissionTest`、`CitelProgrammingResultTest`，共 32 项。覆盖：AC／等待／错误状态、6 个用例解析、嵌套表格、activity a 地址、已通过无表单、不重复 POST、测试中到 AC、刷新失败暂停、缓存任务更新及退出后停止结果读取。

生产 `ProgrammingJudgeResult` 组件使用测试数据渲染浅色与深色的 AC（6/6）、RJ（测试中）、WA（3/6）三个状态。图片在 `multiplatform/shared/build/reports/citel-ui/programming-judge-*.png`，交付副本在 `.artifacts/citel-programming-2026-10-08/`。这是 Windows Compose 渲染，不是 iOS 截图。
