(ns docphoto.guidelines
  (:use [hiccup.element :only (link-to)]))

(defn link
  "gudielines have many cases where the text of the link is the link itself"
  [url]
  (link-to url url))

(defn interest-list
  "moving walls interest list section builder"
  [heading & items]
  (list
   [:h3 heading]
   [:ul
    (for [item items]
      [:li item])]))

(defn defined-item
  "central asia uses these for most sections"
  [title body]
  [:p [:strong title] body])

(def guidelines
  {:mw20
   {:en
    (list
     [:p "The Open Society Foundations invite photographers to submit a body of work for consideration in the Moving Walls 20 group exhibition, scheduled to open in New York in early 2013."]
     [:p "The Moving Walls exhibition series showcases documentary photography that highlights human rights and social issues that coincide with the Open Society Foundations' mission. Moving Walls is exhibited at our offices in New York and Washington, D.C. "]
     [:p "For more details and the application instructions, please visit: " (link "http://www.soros.org/initiatives/photography/focus_areas/mw/guidelines")]
     [:p "If you have any questions, please contact Felix Endara, Exhibitions Coordinator, at 212-547-6909 or " (link-to "mailto:docphoto@sorosny.org" "docphoto@sorosny.org") "."]
     [:p "Moving Walls 20 deadline: Wednesday, May 2nd, 2012, 5 p.m. EST."]
     [:p "To apply, please visit: " (link "http://apply.movingwalls.org/exhibit/mw20/apply")])}

   :prodgrant2012
   {:en
    (list
     [:p "The Open Society Documentary Photography Project and the Arts and Culture Program are offering grants for documentary photographers from Central Asia, the South Caucasus, Afghanistan, Mongolia, and Pakistan. With these grants, we support visual documentation of important human rights and social issues in the region and provide training and mentorship to local photographers.  For more details and the application instructions, please visit: " (link "http://www.soros.org/initiatives/photography/focus_areas/production-individual/guidelines")]
     [:p "If you have any questions, please contact Anna Overstrom-Coleman, Program Assistant, at 212-506-0009 or " (link-to "mailto:aoverstrom-coleman@sorosny.org" "aoverstrom-coleman@sorosny.org") "."]
     [:p "CAC 2012 deadline: Thursday, May 10, 2012, 5 p.m. EST."]
     [:p "To apply, please visit: " (link "http://docphoto.soros.org/exhibit/prodgrant2012/apply")])
    :ru
    (list
     [:p "«Проект в области документальной фотографии» и «Программа по искусству и культуре» прелагают гранты для фотографов-документалистов из Центральной Азии, Южного Кавказа, Афганистана, Монголии и Пакистана. При помощи данных грантов мы поддерживаем визуальное документирование  важных вопросов прав человека и социальных тем региона и предоставляем тренинг и наставническую профессиональную поддержку местным фотографам.  Более подробная информация и инструкции по оформлению заявки – пожалуйста, посетите вебсайт: " (link "http://www.soros.org/initiatives/photography/focus_areas/production-individual/guidelines")]
     [:p "Если у Вас возникли вопросы, пожалуйста, обращайтесь к Анне Overstrom-Коулман, ассистент программы, в 212-506-0009 или " (link-to "mailto:aoverstrom-coleman@sorosny.org" "aoverstrom-coleman@sorosny.org") "."]
     [:p "CAC 2012 срок: четверга, 10 Мая 2012, 5 вечера EST."]
     [:p "Чтобы подать заявку, пожалуйста, посетите: " (link "http://docphoto.soros.org/exhibit/prodgrant2012/apply")])}})
