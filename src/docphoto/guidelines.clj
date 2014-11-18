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
     [:p "If you have any questions, please contact Felix Endara, Exhibitions Coordinator, at 212-547-6909 or " (link-to "mailto:movingwalls@opensocietyfoundations.org" "movingwalls@opensocietyfoundations.org") "."]
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
     [:p "Чтобы подать заявку, пожалуйста, посетите: " (link "http://docphoto.soros.org/exhibit/prodgrant2012/apply")])}

   :mw21
   {:en
    (list
     [:p "The Open Society Foundations invite photographers to submit a body of work for consideration in the Moving Walls 21 group exhibition, scheduled to open in New York in Fall 2013."]
     [:p "The Moving Walls exhibition series showcases documentary photography that highlights human rights and social issues that coincide with the Open Society Foundations' mission. Moving Walls is exhibited at our offices in New York and Washington, D.C. "]
     [:p "For more details and the application instructions, please visit: " (link "http://www.opensocietyfoundations.org/grants/moving-walls")]
     [:p "If you have any questions, please contact Felix Endara, Exhibitions Coordinator, at 212-547-6909 or " (link-to "mailto:movingwalls@opensocietyfoundations.org" "movingwalls@opensocietyfoundations.org") "."]
     [:p "Moving Walls 21 deadline: Tuesday, February 26, 2013, 5 p.m. EST."]
     [:p "To apply, please visit: " (link "http://apply.movingwalls.org/21/apply")])}

   :prodgrant2013
   {:en
    (list
     [:p "The Open Society Documentary Photography Project is offering grants for documentary photographers from Central Asia, the South Caucasus, Afghanistan, Mongolia, and Pakistan. With these grants, we support visual documentation of important human rights and social issues in the region and provide training and mentorship to local photographers.  For more details and the application instructions, please visit: " (link "http://www.opensocietyfoundations.org/grants/production-grants-individuals")]
     [:p "If you have any questions, please contact Anna Overstrom-Coleman, Program Assistant, at 212-506-0009 or " (link-to "mailto:aoverstrom-coleman@sorosny.org" "aoverstrom-coleman@sorosny.org") "."]
     [:p "CAC 2013 deadline: Tuesday, March 5, 2013, 5 p.m. EST."]
     [:p "To apply, please visit: " (link "http://docphoto.soros.org/2013/apply")])
    :ru
    (list
     [:p "<Проект Открытого Общества в области документальной фотографии> (Documentary Photography Project) предлагает гранты для фотографов-документалистов из Центральной Азии, Южного Кавказа, Афганистана, Монголии и Пакистана. При помощи данных грантов мы поддерживаем визуальное документирование  важных вопросов прав человека и социальных тем региона и предоставляем тренинг и наставническую профессиональную поддержку местным фотографам. Более подробная информация и инструкции по оформлению заявки - пожалуйста, посетите вебсайт: " (link "http://www.opensocietyfoundations.org/grants/production-grants-individuals")]
     [:p "Если у Вас возникли вопросы, пожалуйста, обращайтесь к Анне Overstrom-Коулман, ассистент программы, в 212-506-0009 или " (link-to "mailto:aoverstrom-coleman@sorosny.org" "aoverstrom-coleman@sorosny.org") "."]
     [:p "CAC 2013 срок: Вторник, 5 март 2013, 5 вечера EST."]
     [:p "Чтобы подать заявку, пожалуйста, посетите: " (link "http://docphoto.soros.org/2013/apply")])}

   :mw22
   {:en
    (list
     [:p "The Open Society Foundations Documentary Photography Project invites photographers to submit a body of work for consideration in the "
      [:strong "Moving Walls 22 / Watching You, Watching Me"]
      " group exhibition, scheduled to open at the Open Society Foundations' office in New York on October 29, 2014."]
     [:p "The Moving Walls exhibition series showcases documentary photography that highlights human rights and social issues that coincide with the Open Society Foundations' mission. For the fall 2014 exhibition, we are focusing on the theme of surveillance."]
     [:p " Moving Walls is exhibited at our offices in New York, Washington, D.C., and London."]
     [:p "For more details and the application instructions, please visit: " (link "http://www.opensocietyfoundations.org/grants/moving-walls")]
     [:p "If you have any questions, please contact the Open Society Documentary Photography Project at 212-547-6909 or " (link-to "mailto:movingwalls@opensocietyfoundations.org" "movingwalls@opensocietyfoundations.org") "."]
     [:p "Moving Walls 22 deadline: Thursday, May 1, 2014, 5 p.m. EST."]
     [:p "To apply, please visit: " (link "http://apply.movingwalls.org/22/apply")])}

   :mw23
   {:en
    (list
     [:p "The Open Society Documentary Photography Project invites photographers to submit a body of work for consideration in the Moving Walls 23 group exhibition (opens in New York in June 2015)."]
     [:p "We are seeking photo-based documentary projects that address a social justice or human rights issue in any region where the Open Society Foundations is active (see our " [:a {:href "http://opensocietyfoundations.org/"} "website"] " for more details)."]
     [:p "We are particularly interested in work that provides a fresh perspective and expands the visual language of documentary photography. Therefore, we invite photographers and artists to submit documentary-based work that uses new or creative visual strategies, investigative/storytelling approaches, or technologies. We seek applicants who reflect a diversity of global perspectives, especially those that are under-represented in mainstream narratives and media."]
     [:p "For more details and the application instructions, please visit: " (link "http://www.opensocietyfoundations.org/grants/moving-walls")]
     [:p "If you have any questions, please contact the Open Society Documentary Photography Project at 212-547-6909 or " (link-to "mailto:movingwalls@opensocietyfoundations.org." "movingwalls@opensocietyfoundations.org") "."]
     [:p "Moving Walls 23 deadline: Tuesday, November 18, 2014, 5 p.m. EST."]
     [:p "To apply, please visit: " (link "http://apply.movingwalls.org/23/apply")]
     [:p "*It is " [:strong "strongly recommended"] " that applicants work on building their materials (i.e. submission texts, image sequencing, etc) " [:strong [:span {:style "text-decoration: underline"} "before"]] " creating an online submission.  Due to heavy traffic the application portal may experience issues with uploading/saving, especially close to the deadline.  Please use the application guidelines to prepare materials in advance, and then use the portal when ready to upload and submit the final application."]
     [:p "Please also press the &quot;save progress&quot; button before exiting the system and coming back to it. Otherwise, the work will not be saved."])}
   })
