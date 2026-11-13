// SPDX-License-Identifier: GPL-2.0-or-later
package net.local.p1007bridge;

/** Self-contained page: no CDN, external requests, third-party scripts or uploads. */
public final class WebPage {
    private WebPage() { }
    public static String html(String token) {
        // Token is generated internally as a hexadecimal string, never from user input.
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>P1007 打印</title>"
            + "<style>*{box-sizing:border-box}body{margin:0;background:#f3f5f7;color:#1e293b;font:16px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}"
            + "main{max-width:650px;margin:0 auto;padding:28px 18px 48px}h1{font-size:27px;line-height:1.3;margin:0 0 6px}p{margin:0 0 18px;color:#64748b}.card{background:white;border:1px solid #e2e8f0;border-radius:18px;padding:22px;margin-top:20px}"
            + ".status{font-size:14px;color:#475569}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#16a34a;margin-right:7px}label{display:block;font-weight:600;margin-bottom:9px}"
            + "input[type=file]{display:block;width:100%;padding:14px 10px;background:#f8fafc;border:1px dashed #94a3b8;border-radius:10px;font:inherit;font-size:14px}"
            + "input[type=text]{width:100%;border:1px solid #cbd5e1;border-radius:9px;padding:11px 12px;font:inherit}input[type=text]:disabled{background:#f1f5f9;color:#94a3b8}.pages{margin-top:20px}"
            + ".help{font-size:13px;color:#64748b;margin-top:9px}.row{display:flex;gap:22px;align-items:center;margin:24px 0}.field{flex:1}.field label{font-size:14px;margin-bottom:5px}"
            + "select{min-width:90px;padding:9px 12px;border:1px solid #cbd5e1;border-radius:8px;background:white;color:inherit;font:inherit}.fixed{padding:9px 0;color:#475569}"
            + "button{width:100%;border:0;border-radius:11px;padding:13px 16px;background:#1665d8;color:white;font:inherit;font-weight:600;cursor:pointer}button:disabled{background:#94a3b8;cursor:wait}"
            + "#message{min-height:24px;font-size:14px;margin-top:12px;white-space:pre-wrap}h2{font-size:18px;margin:0 0 12px}.job{padding:13px 0;border-top:1px solid #edf0f4}.job:first-child{border-top:0}.name{font-weight:500;overflow-wrap:anywhere}.meta{color:#64748b;font-size:13px}.error{color:#b91c1c}.empty{color:#94a3b8;font-size:14px}.sent{color:#15803d}.notice{font-size:13px;color:#64748b;margin:18px 2px 0}"
            + "</style></head><body><main><h1>P1007 打印</h1><p>选择文件，在家里直接打印。</p><div class=\"status\"><span class=\"dot\"></span><span id=\"status\">正在连接打印机…</span></div>"
            + "<section class=\"card\"><label for=\"file\">选择文件</label><input id=\"file\" type=\"file\" accept=\".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png\">"
            + "<div class=\"help\">支持 PDF、JPG、PNG，单个文件不超过 20MB；总打印量不超过 100 页。</div>"
            + "<div class=\"pages\"><label for=\"pages\">页码（PDF）</label><input id=\"pages\" type=\"text\" maxlength=\"200\" placeholder=\"留空打印全部，例如 1,3-5\" disabled><div class=\"help\">选择 PDF 后可指定页码；图片按单页打印。</div></div>"
            + "<div class=\"row\"><div class=\"field\"><label>纸张</label><div class=\"fixed\">A4</div></div><div class=\"field\"><label for=\"copies\">份数</label><select id=\"copies\">"
            + "<option>1</option><option>2</option><option>3</option><option>4</option><option>5</option><option>6</option><option>7</option><option>8</option><option>9</option><option>10</option></select></div>"
            + "<div class=\"field\"><label>打印方式</label><div class=\"fixed\">黑白</div></div></div><button id=\"print\" type=\"button\">提交打印</button><div id=\"message\" role=\"status\" aria-live=\"polite\"></div></section>"
            + "<section class=\"card\"><h2>最近的任务</h2><div id=\"jobs\" class=\"empty\">正在读取…</div></section>"
            + "<div class=\"notice\">“已发送”表示任务已传给打印机，请以实际出纸为准。任务结束后会删除临时文件。</div></main>"
            + "<script>'use strict';const token='" + token + "';const file=document.getElementById('file'),pages=document.getElementById('pages'),button=document.getElementById('print'),message=document.getElementById('message');"
            + "const names={uploading:'上传中',queued:'排队中',processing:'处理中',sent:'已发送',failed:'失败'};let polling=false;"
            + "function mimeFor(f){const n=f.name.toLowerCase();if(/\\.pdf$/.test(n))return 'application/pdf';if(/\\.jpe?g$/.test(n))return 'image/jpeg';if(/\\.png$/.test(n))return 'image/png';return '';}"
            + "file.addEventListener('change',function(){const f=file.files[0];pages.disabled=!f||mimeFor(f)!=='application/pdf';if(pages.disabled)pages.value='';});"
            + "button.addEventListener('click',async function(){const f=file.files[0];message.className='';if(!f){message.textContent='请先选择一个文件。';return;}const mime=mimeFor(f);"
            + "if(!mime){message.textContent='请选择 PDF、JPG 或 PNG 文件。';return;}if(!f.size||f.size>20*1024*1024){message.textContent='文件为空或超过 20MB，请换一个文件。';return;}"
            + "button.disabled=true;message.textContent='正在上传，请保持页面打开…';try{const response=await fetch('/api/jobs?copies='+document.getElementById('copies').value+'&pages='+encodeURIComponent(mime==='application/pdf'?pages.value:''),{method:'POST',headers:{'Content-Type':mime,'X-File-Name':encodeURIComponent(f.name),'X-Print-Token':token},body:f,cache:'no-store'});"
            + "const result=await response.json();if(!response.ok)throw new Error(result.error||'提交失败，请稍后重试。');message.textContent='已提交，任务状态会在下方更新。';file.value='';pages.value='';pages.disabled=true;await refresh();}"
            + "catch(e){message.className='error';message.textContent=(e instanceof TypeError)?'网络连接中断，请先检查下方任务记录，避免重复提交。':(e.message||'提交失败，请稍后重试。');}finally{button.disabled=false;}});"
            + "async function refresh(){if(polling)return;polling=true;try{const responses=await Promise.all([fetch('/api/status',{cache:'no-store'}),fetch('/api/jobs',{cache:'no-store'})]);if(!responses[0].ok||!responses[1].ok)throw new Error('offline');"
            + "const state=await responses[0].json(),data=await responses[1].json();document.getElementById('status').textContent=state.status||'打印服务已开启';const list=document.getElementById('jobs');list.textContent='';list.className='';"
            + "if(!data.jobs.length){list.className='empty';list.textContent='还没有打印任务';}data.jobs.forEach(function(job){const row=document.createElement('div');row.className='job';const title=document.createElement('div');title.className='name';title.textContent=job.name;"
            + "const detail=document.createElement('div');detail.className='meta'+(job.state==='failed'?' error':job.state==='sent'?' sent':'');const date=new Date(job.created);detail.textContent=(names[job.state]||job.state)+' · '+job.copies+' 份 · '+(job.pages?'第 '+job.pages+' 页':'全部页')+' · '+date.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});row.appendChild(title);row.appendChild(detail);"
            + "if(job.error){const error=document.createElement('div');error.className='meta error';error.textContent=job.error;row.appendChild(error);}list.appendChild(row);});}catch(e){document.getElementById('status').textContent='暂时无法连接，请检查魔盒网络';}finally{polling=false;}}"
            + "refresh();setInterval(refresh,2000);</script></body></html>";
    }
}
