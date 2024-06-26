document.addEventListener('DOMContentLoaded',function(){

    const nav = document.querySelector('.navbar')
    const allNavItems = document.querySelectorAll('.nav-link')
    const navList = document.querySelector('.navbar-collapse')
    const btn = document.querySelector('.navbar-toggler')

    function addShadow(){
        if (nav==null) return;
        if (window.scrollY>=100) {
            nav.classList.add('shadow-bg')
        }
        else if(window.scrollY==0){
            nav.classList.remove('shadow-bg')
        }

    }

    function addShadowClick (){
        nav.classList.add('shadow-bg')
    }

    allNavItems.forEach(item => item.addEventListener('click',()=> navList.classList.remove('show')))

    if (btn!=null) btn.addEventListener('click', addShadowClick)
    window.addEventListener('scroll', addShadow)

})