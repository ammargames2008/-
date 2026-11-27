(function(){
  if(window.__apkPatched||!window.Android)return;
  window.__apkPatched=true;
  function handle(a){
    try{
      if(!a||!a.hasAttribute||!a.hasAttribute('download'))return false;
      var h=a.href||'';
      if(h.indexOf('blob:')!==0&&h.indexOf('data:')!==0)return false;
      var name=a.getAttribute('download')||'file';
      fetch(h).then(function(r){return r.blob()}).then(function(b){
        var fr=new FileReader();
        fr.onloadend=function(){
          var s=String(fr.result);
          Android.saveFile(name,b.type||'application/octet-stream',s.substring(s.indexOf(',')+1));
        };
        fr.readAsDataURL(b);
      });
      return true;
    }catch(e){return false}
  }
  var oc=HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click=function(){ if(handle(this))return; return oc.apply(this,arguments); };
  var od=EventTarget.prototype.dispatchEvent;
  EventTarget.prototype.dispatchEvent=function(e){
    if(this instanceof HTMLAnchorElement&&e&&e.type==='click'&&handle(this))return true;
    return od.apply(this,arguments);
  };
})();
