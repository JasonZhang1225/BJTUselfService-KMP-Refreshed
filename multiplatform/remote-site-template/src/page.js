import { createRefreshMonitor } from "./refresh-monitor.js";

export const page = `<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark"><title>交大自由行 · 远程助手</title>
<style>
:root{font-family:system-ui,"Microsoft YaHei",sans-serif;color:#173027;background:#f3f6f2;color-scheme:light dark}*{box-sizing:border-box}body{margin:0}main{max-width:1100px;margin:auto;padding:40px 24px}header{display:flex;justify-content:space-between;gap:16px;align-items:center;border-bottom:1px solid #ccd8d0;padding-bottom:24px}.brand{font-weight:700;font-size:14px;letter-spacing:.05em;color:#38705a}h1{font-size:clamp(28px,5vw,44px);letter-spacing:-.04em;margin:14px 0}p{line-height:1.7}small,.muted{color:#617269}.pill{background:#e1eadf;padding:8px 12px;border-radius:20px;white-space:nowrap}.grid{display:grid;grid-template-columns:1.15fr 1fr;gap:20px;margin-top:24px}.card{background:#fff;border:1px solid #dce4dc;border-radius:20px;padding:24px;min-width:0}h2{font-size:18px;margin:0 0 18px}button{font:inherit;background:#245e45;color:white;border:0;border-radius:10px;padding:12px 16px;cursor:pointer;min-height:44px}button:active{opacity:.7}button:disabled{opacity:.5;cursor:wait}button.secondary{background:#e8eee8;color:#234c38}button.danger{background:#fff0eb;color:#8f3525}button:focus-visible,a:focus-visible{outline:3px solid #cba94f;outline-offset:3px}.actions{display:flex;gap:10px;flex-wrap:wrap}dl{display:grid;grid-template-columns:110px 1fr;gap:14px;font-size:14px}dt{color:#617269}dd{margin:0;overflow-wrap:anywhere}.list{display:grid;gap:10px}.row{border-top:1px solid #e1e8df;padding:12px 0;display:flex;justify-content:space-between;gap:16px}.row span{overflow-wrap:anywhere}#notice{min-height:30px;margin:20px 0 0;overflow-wrap:anywhere}#pair{font-size:13px;font-variant-numeric:tabular-nums;white-space:pre-wrap}.wide{grid-column:1/-1}.note{font-size:13px;margin:20px 0 0}pre{white-space:pre-wrap;overflow-wrap:anywhere}footer{font-size:13px;margin-top:24px;color:#617269}@media(max-width:700px){main{padding:24px 16px}.grid{grid-template-columns:1fr}header{align-items:flex-start}.card{padding:20px}dl{grid-template-columns:90px 1fr}.wide{grid-column:auto}}@media(prefers-color-scheme:dark){:root{background:#14201b;color:#e0eade}.card{background:#1c2b23;border-color:#35473a}.muted,small,dt,footer{color:#a6b7a6}.pill{background:#304632}.brand{color:#a9d3b4}.row,header{border-color:#35473a}button{background:#add6b7;color:#14251b}button.secondary{background:#344838;color:#dcebdc}button.danger{background:#4a2d26;color:#f8bca9}}

.refresh-progress{margin-top:20px;padding:16px;border:1px solid #ccd8d0;border-radius:12px;background:color-mix(in srgb,Canvas 92%,#38705a)}.refresh-progress p{margin:8px 0;font-size:14px}.refresh-progress small{display:block;overflow-wrap:anywhere}.refresh-progress ul{padding-left:20px;font-size:13px;line-height:1.7;margin:8px 0 0}.refresh-progress[data-phase="succeeded"]{border-color:#38705a}.refresh-progress[data-phase="partial"],.refresh-progress[data-phase="failed"],.refresh-progress[data-phase="expired"]{border-color:#aa753c}
</style><main><header><div><div class="brand">交大自由行 / REMOTE</div><h1>校园安排，随时可查。</h1><p class="muted">电脑定时同步，手机刷新后上传，私人 Site 保留快照。普通查询直接读取快照。</p></div><span class="pill">私人空间</span></header>
<p>首次打开本站时，请按平台提示登录。登录后点击“生成配对信息”，回到 App 完成配对。只在 ChatGPT 查询已有电脑数据时，无需额外配对手机 App。</p>
<p id="notice" role="status" aria-live="polite">正在读取连接状态…</p><div class="grid">
<section class="card"><h2>我的设备</h2><dl id="device"><dt>设备</dt><dd>加载中</dd></dl><div class="actions"><button id="reload">刷新页面数据</button><button id="refresh" class="secondary">请求电脑全量刷新</button></div><div id="refresh-progress" class="refresh-progress" role="status" aria-live="polite" aria-atomic="true"><strong id="refresh-title">尚未请求刷新</strong><p id="refresh-detail">提交后会自动显示进度。</p><small id="refresh-meta"></small><ul id="refresh-results"></ul></div></section>
<section class="card"><h2>连接设备</h2><p class="muted">在各端 App 的应用 → Agent 和 ChatGPT 插件中，只粘贴完整配对长码，无需填写地址，再选择允许上云的数据。手机刷新后上传，不轮询即时任务；即时刷新由在线电脑执行。</p><button id="pairing" class="secondary">生成配对信息</button><pre id="pair" aria-live="polite"></pre><p class="note muted">配对信息 10 分钟有效，仅能使用一次。完整长码已包含本站地址和连接授权，请完整复制到新版 App；不要发送到聊天或分享给他人。学校密码始终留在设备。</p></section>
<section class="card wide"><h2>各来源同步时间</h2><div id="sources" class="list">等待快照</div><p class="note muted">下列时间来自学校数据实际同步记录；接收快照的时间不代表学校数据已刷新。</p></section>
<section class="card wide"><h2>数据与设备管理</h2><div class="actions"><button id="pause" class="secondary">暂停远程查询</button><button id="revoke" class="danger">撤销当前设备</button><button id="clear" class="danger">清除云端快照与任务</button></div><p class="note muted">撤销后设备令牌失效。清云端数据保留设备绑定；设备仍开启同步时，之后会重新上传。</p></section></div><footer>北京时间 · 午夜 00:00 截止按前一天 24:00 提醒 · 远程助手不提供提交作业、发邮件或远程终端</footer></main>
<script type="module">
const createRefreshMonitor = ${createRefreshMonitor.toString()};
let state, currentTask, taskSignature;
const $=id=>document.getElementById(id);
const time=value=>value?new Date(value*1000).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):'尚无记录';
const sourceNames={courses:'课程',exams:'考试',homework:'作业',physicsLab:'物理实验',physicsDemo:'演示实验',citel:'CITEL',phyvlab:'物理在线',grades:'成绩',mail:'邮箱未读数'};
const sourceStates={success:'已同步',skipped:'已跳过',failed:'失败',partial:'部分完成',needs_user_action:'需在电脑处理'};
async function api(path,method='GET',body){
  const r=await fetch('/api/v1/owner/'+path,{method,cache:'no-store',headers:body?{'content-type':'application/json'}:{},body:body?JSON.stringify(body):undefined});
  let data;try{data=await r.json()}catch{const error=Error(r.status===401||r.status===403?'请登录本站后继续。':'Site 返回了无法识别的响应，请稍后重试。');error.status=r.status;throw error}
  if(!r.ok){const error=Error(data.error?.message||'暂时无法连接');error.status=r.status;error.code=data.error?.code;throw error}return data;
}
function text(tag,value){const el=document.createElement(tag);el.textContent=value;return el}
function renderButton(){const button=$('refresh'),busy=monitor.busy();button.disabled=busy||!state?.taskDeviceId||state?.queriesPaused;button.textContent=busy?(currentTask?.status==='queued'?'等待电脑领取…':currentTask?.status==='running'?'电脑正在刷新…':'正在提交…'):'请求电脑全量刷新';}
function renderTask(task){
  currentTask=task;renderButton();
  const signature=JSON.stringify(task);if(signature===taskSignature)return;taskSignature=signature;
  const phase=task?.status||'idle';$('refresh-progress').dataset.phase=phase;$('refresh-progress').setAttribute('aria-busy',String(['queued','running'].includes(phase)));
  const labels={idle:'尚未请求刷新',queued:'已排队，等待电脑领取',running:'电脑正在刷新并上传',succeeded:'刷新完成',partial:'刷新部分完成',failed:'刷新失败',expired:'请求已过期',revoked:'设备绑定已撤销',unavailable:'任务记录已清除或不可用'};
  const descriptions={idle:'提交后会在这里自动显示进度，无需手动刷新页面。',queued:'电脑默认每 5 秒检查一次指令。离线时需等待电脑重新连接；不用等半小时同步。',running:'电脑已领取指令，正在查询学校并上传快照；部分来源可能需要较长时间。',succeeded:'已收到电脑执行结果，各来源同步时间已自动更新。',partial:'部分来源未完成；成功来源已更新，失败来源保留原缓存和原同步时间。',failed:'请求未能完成，请查看下方原因；原有快照仍可读取。',expired:'电脑未在有效期内领取指令，请确认电脑在线、已登录且远程同步开启后再试。',revoked:'这条请求所属的设备绑定已经失效，请在电脑端重新连接。',unavailable:'这条任务的记录已不存在，页面会继续自动读取设备状态。'};
  $('refresh-title').textContent=labels[phase]||'正在检查刷新状态';
  $('refresh-detail').textContent=task?.result?.data?.snapshotUploaded===false?'电脑已执行刷新，但快照尚未成功上传；当前仍显示已接收数据的时间。':descriptions[phase]||'';
  $('refresh-meta').textContent=task?('提交：'+time(task.createdAt)+(task.completedAt?' · 完成：'+time(task.completedAt):'')+' · 请求 '+task.requestId):'';
  $('refresh-results').replaceChildren();
  if(task?.result?.error?.message)$('refresh-results').append(text('li',task.result.error.message.slice(0,200)));
  for(const source of task?.result?.data?.refresh?.sources||[]){
    const label=sourceNames[source.source]||source.source;
    if(source.status!=='success')$('refresh-results').append(text('li',label+'：'+(sourceStates[source.status]||source.status)+(source.reason?'（'+source.reason.slice(0,160)+'）':'')));
  }
}
function renderStatus(value){
  state=value;const devices=state.devices.filter(d=>!d.revokedAt),device=devices[0];$('device').replaceChildren();
  for(const d of devices)$('device').append(text('dt',d.deviceName),text('dd',d.deviceKind==='mobile'?'手机 / 平板 · 刷新后上传，不轮询':'电脑 · 定时同步并轮询即时任务'));
  for(const [k,v]of [['设备',device?.deviceName||'尚未配对'],['最近在线',time(device?.lastSeenAt)],['快照接收',time(state.snapshot?.receivedAt)],['允许范围',device?.scopes.join('、')||'尚未授权']])$('device').append(text('dt',k),text('dd',v));
  $('sources').replaceChildren();
  for(const [name,source]of Object.entries(state.snapshot?.sources||{})){const row=text('div','');row.className='row';row.append(text('span',(sourceNames[name]||name)+' · '+(sourceStates[source.status]||source.status)),text('span',time(source.fetchedAt)));$('sources').append(row)}
  if(!$('sources').children.length)$('sources').append(text('p','尚无快照；配对后在 App 刷新并上传一次。'));
  $('revoke').textContent=device?'撤销 '+device.deviceName:'撤销当前设备';$('revoke').disabled=!device;
  $('pause').textContent=state.queriesPaused?'恢复远程查询':'暂停远程查询';
  $('notice').textContent=state.queriesPaused?'远程查询已暂停。':device?'已连接；页面会自动更新设备与刷新状态。':'尚未配对设备。';renderButton();
}
let storage;try{storage=sessionStorage}catch{}
const monitor=createRefreshMonitor({api,onStatus:renderStatus,onTask:renderTask,onBusy:busy=>{renderButton();if(busy&&!currentTask){$('refresh-title').textContent='正在提交刷新请求';$('refresh-detail').textContent='正在把请求送到你的私人 Site…';}},onError:message=>{$('notice').textContent=message;},storage,visible:()=>!document.hidden});
function action(id,fn){$(id).onclick=async()=>{$(id).disabled=true;try{await fn()}catch(e){$('notice').textContent=e.message}finally{$(id).disabled=false}}}
action('reload',()=>monitor.wake());
$('refresh').onclick=()=>monitor.requestRefresh();
action('pairing',async()=>{const p=await api('connection','POST');$('pair').textContent=p.connectionCode;$('copy-pair').hidden=false;$('notice').textContent='请完整复制配对信息到新版 App，有效期 10 分钟。'});
const copyPair=document.createElement('button');copyPair.id='copy-pair';copyPair.className='secondary';copyPair.textContent='复制配对信息';copyPair.hidden=true;$('pair').after(copyPair);
action('copy-pair',async()=>{try{await navigator.clipboard.writeText($('pair').textContent);$('notice').textContent='配对信息已复制。'}catch{$('notice').textContent='浏览器未允许复制，请选择配对信息手动复制。'}});
action('pause',async()=>{await api('queries','PUT',{paused:!state.queriesPaused});await monitor.wake()});
action('revoke',async()=>{const d=state?.devices.find(d=>!d.revokedAt);if(d&&confirm('撤销这台设备？其设备令牌将失效，需要重新配对。')){await api('devices/'+encodeURIComponent(d.deviceId)+'/revoke','POST');await monitor.wake()}});
action('clear',async()=>{if(confirm('清除所有云端快照与任务？仍开启同步的设备之后可能重新上传。')){await api('data','DELETE');await monitor.wake()}});
document.addEventListener('visibilitychange',()=>{if(!document.hidden)void monitor.wake()});
window.addEventListener('pagehide',()=>monitor.stop());window.addEventListener('pageshow',()=>monitor.start());
monitor.start();
</script>
</html>`;
