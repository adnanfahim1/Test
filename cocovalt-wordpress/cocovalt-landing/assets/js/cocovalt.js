/* Cocovalt Landing: hero interactions, cocoa dust and the construction tape. */
(function(){
  var hero=document.getElementById('cv-hero'),stage=document.getElementById('cv-stage'),
      btn=document.getElementById('cv-logo-btn'),tilt=document.getElementById('cv-tilt'),hint=document.getElementById('cv-hint');
  if(!hero||!stage||!btn||!tilt||!hint){return;}

  /* click logo: open / close panels + arrows */
  btn.addEventListener('click',function(){
    var open=!stage.classList.contains('is-open');
    stage.classList.toggle('is-open',open);
    btn.setAttribute('aria-expanded',open?'true':'false');
    hint.textContent=open?hint.getAttribute('data-label-open'):hint.getAttribute('data-label-closed');
  });

  /* gentle parallax of the background photo */
  var raf;
  hero.addEventListener('mousemove',function(e){
    var r=hero.getBoundingClientRect(),
        x=((e.clientX-r.left)/r.width)*2-1,y=((e.clientY-r.top)/r.height)*2-1;
    cancelAnimationFrame(raf);
    raf=requestAnimationFrame(function(){hero.style.setProperty('--mx',x.toFixed(3));hero.style.setProperty('--my',y.toFixed(3));});
  });
  hero.addEventListener('mouseleave',function(){hero.style.setProperty('--mx',0);hero.style.setProperty('--my',0);});

  /* logo tilts toward cursor only while hovered */
  btn.addEventListener('mousemove',function(e){
    var r=btn.getBoundingClientRect(),
        x=((e.clientX-r.left)/r.width)*2-1,y=((e.clientY-r.top)/r.height)*2-1;
    tilt.style.transform='rotateX('+(-y*6).toFixed(2)+'deg) rotateY('+(x*8).toFixed(2)+'deg) translate('+(x*4).toFixed(1)+'px,'+(y*3).toFixed(1)+'px)';
  });
  btn.addEventListener('mouseleave',function(){tilt.style.transform='';});

  /* rising cocoa dust */
  var dust=document.getElementById('cv-dust'),seed=31;
  function rnd(){seed=(seed*9301+49297)%233280;return seed/233280;}
  if(dust){
    for(var i=0;i<28;i++){
      var s=document.createElement('span'),sz=(1.5+rnd()*2.5).toFixed(1);
      s.className='cv-dust';
      s.style.left=(rnd()*100).toFixed(1)+'%';s.style.width=s.style.height=sz+'px';
      s.style.animationDuration=(16+rnd()*14).toFixed(1)+'s';s.style.animationDelay=(-rnd()*30).toFixed(1)+'s';
      dust.appendChild(s);
    }
  }

  /* tape: repeat text twice for a seamless loop */
  var track=document.getElementById('cv-tape-track');
  if(track){
    var text=track.getAttribute('data-text');
    for(var j=0;j<16;j++){var w=document.createElement('span');w.textContent=text;track.appendChild(w);}
  }
})();
