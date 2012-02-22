(ns docphoto.guidelines
  (:use [hiccup.page-helpers :only (link-to)]))

(defn interest-list [heading & items]
  (list
   [:h3 heading]
   [:ul
    (for [item items]
      [:li item])]))

(def guidelines
  {:mw20  (list
           [:p {:style "margin-bottom: 4em"}
            "Once you have read the guidelines, you can "
            (link-to "http://apply.movingwalls.org/exhibit/mw20/apply" "apply")
            " to Moving Walls 20"]
           [:h1 "Open Society Foundations"]
           [:h1 "Documentary Photography Project"]
           [:h1 "Moving Walls 20"]
           [:h1 "Application Guidelines"]
           [:h2 {:style "margin-top: 2em"} "Overview"]
           [:p "The Open Society Foundations invite photographers to submit a body of work for consideration in the Moving Walls 20 group exhibition, scheduled to open in New York in early 2013."]
           [:p "The Moving Walls exhibition series showcases documentary photography that highlights human rights and social issues that coincide with the Open Society Foundations' mission. Moving Walls is exhibited at our offices in New York and Washington, D.C. "]
           [:p "Launched in 1998, Moving Walls has featured over 175 photographers. Over the past 14 years, we have been proud to support the brave and difficult (and often self-funded) work that photographers undertake globally in their visual documentation of complex social and political issues. Their images provide the world with evidence of human rights abuses, put faces onto a conflict, document the struggles and defiance of marginalized people, reframe how issues are discussed publicly, and provide opportunities for reflection and discussion. Moving Walls honors this work while visually highlighting the Open Society Foundations' mission to staff and visitors."]
           [:p " For participating photographers, a key benefit of the program is to gain exposure for their projects, as well as the social justice or human rights issues they address. In addition to a $2000 honorarium, photographers receive their professionally-produced exhibitions at the end of the exhibition tour in NY and Washington, D.C."]
           [:p "With this next competition, Moving Walls will celebrate its 20th exhibition cycle. Moving Walls 20 will also be the inaugural exhibition in the Open Society Foundations' new headquarters on West 57th Street in New York. We intend to showcase three to five discrete bodies of work, depending on the scope of the selected projects."]
           [:p "To view images from our recent exhibitions, please visit: " (link-to "http://www.movingwalls.org" "www.movingwalls.org") "."]
           [:h2 "Areas of Interest"]
           [:p "Each Moving Walls exhibit highlights issues or geographic regions where the Open Society Foundations are active. Priority is given to work whose subject has not been recently addressed in Moving Walls, and special consideration is given to long-term work produced over years of commitment to an issue or community. Work in progress may be submitted as long as a substantial portion of the work has been completed."]

           [:p "Listed below are some focus areas for the Open Society Foundations, and examples of specific topics about which we are interested in receiving submissions. Please note that photographers are welcome to submit their work for Moving Walls even if their subject area is not included on this list. All work submitted will be considered for exhibition. In addition to the focus areas listed below, please visit our website (" (link-to "http://www.soros.org/" "www.soros.org") ") for a complete listing of priorities and programs"]

           [:div.interest-areas
            (interest-list "Justice"
                           "Pretrial detention"
                           "Detention of immigrants, especially in the United States")

            (interest-list "Public health"
                           "Public health issues in Africa, including access to essential medicines, access to health care, palliative care"
                           "Physical and mental disabilities in Eastern Europe or Central Asia, focusing on integration or inclusion")

            (interest-list "Migration"
                           "Migration in Europe, especially Italy"
                           "Migration through Central America")

            (interest-list "Political Turmoil and Change"
                           "Political violence, especially in Latin America and Africa"
                           "Political unrest in Nigeria"
                           "Democratic process in the Middle East"
                           "Creation of South Sudan")

            (interest-list "Economic and Racial Justice"
                           "Economic downturn in the United States, including the foreclosure crisis"
                           "Images that reframe mainstream media representations of African American men and boys")

            (interest-list "Women"
                           "Women in post-conflict countries")

            (interest-list "Youth"
                           "Youth movements, especially political participation in voter registration, policy reform efforts, public education, especially in Eastern Europe")

            (interest-list "Climate Change"
                           "Impact of climate change on communities")]

           [:h2 "Who Can Apply"]

           [:p "Any emerging or veteran photographer who is working long-term to document a human rights or social justice issue may apply for Moving Walls."]

           [:p "Photographers working in their home countries, women, emerging artists, and people of color are strongly encouraged to apply."]

           [:p "The Open Society Foundations does not discriminate based on any status that may be protected by applicable law."]

           [:h2 "Selection Criteria"]

           [:p "We will accept any genre of photography that is documentary in nature and is not staged or manipulated. Priority will be given to work that addresses issues and geographic regions of concern to the Open Society Foundations."]

           [:p "In 2012, three to five portfolios will be selected based on:"]
           [:ul
            [:li "quality of the images"]
            [:li "relevance to the Open Society Foundations"]
            [:li "photographer's ability to portray a social justice or human rights issue in a visually compelling, unique, and respectful way"]
            [:li "photographer's long-term commitment to the issue"]]

           [:h2 "Emerging Photographer Travel Grant"]

           [:p "To support the professional advancement of photographers who have not received much exposure, an additional travel grant will be provided to select Moving Walls photographers to attend the opening in New York and meet with local photo editors and relevant NGO staff."]

           [:p "Recipients must apply for the travel grant " [:strong "after"] " being selected for the Moving Walls exhibition. The grant is subject to the applicant obtaining the necessary visa to travel to the United States."]

           [:p "Recipients will be determined based on, among other things, prior international travel experience, prior attendance at workshops and seminars outside their home communities, publication and exhibition history, awards, and potential impact on their professional development."]

           [:h2 "Application Process"]
           [:p "Photographers must apply online at " (link-to "http://apply.movingwalls.org/exhibit/mw20/apply" "http://apply.movingwalls.org/exhibit/mw20/apply") "."]

           [:p "You will be asked to complete or upload the following:"]
           [:ol
            [:li [:strong "application cover page"] " (200 words maximum) introducing the project you would like to exhibit;"]
            [:li [:strong "a project statement*"] " (600 words maximum) describing the project you would like to exhibit;"]
            [:li [:strong "a short narrative bio"] " (250 words maximum) summarizing your previous work and experience;"]
            [:li [:strong "a summary of your engagement"] " with the story or issue (600 words maximum). Please respond to the following questions:"
             [:ul
              [:li "What is your relationship with the issue or community you photographed?"]
              [:li "How and why did you begin the project?"]
              [:li "How long have you been working on the project?"]
              [:li "Are there particular methods you use while working?"]
              [:li "What do you hope a viewer will take away from your project?"]]]
             [:li [:strong "your curriculum vitae"]]
            [:li [:strong "15-20 jpg images  [up to 5mb per image], with corresponding captions"]]
            [:p [:strong "Optional materials:"]]
            [:li [:strong "Multimedia"] ": Moving Walls has the capacity to exhibit multimedia in addition to (but not in place of) the print exhibition. A multimedia sample should be submitted only if it complements or enhances, rather than duplicates, the other submitted materials. The sample will be judged on its ability to present complex issues through compelling multimedia storytelling, and will not negatively impact the print submission. To submit a multimedia piece for consideration, please post the piece on a free public site such as YouTube or Vimeo and include a link. If the piece is longer than five minutes, let us know what start time to begin watching at."]]

           [:p [:strong "*NOTE"] ": The one-page statement is intended to give the Selection Committee a better understanding of the project. Non-native English speakers should describe their projects as accurately as possible, but do need not be concerned with the quality of their English."]

           [:p "Complete submissions must be " [:u "received"] " via the online application system (" (link-to "http://apply.movingwalls.org/exhibit/mw20/apply" "apply.movingwalls.org") ") by " [:strong "5pm (Eastern Standard Time) on Thursday, April 19, 2012. Please do not wait until immediately prior to the deadline to submit work."] " Due to intake of a high volume of large files, we occasionally experience technical difficulties in the days leading up to the deadline. Please help us to avoid this by submitting early."]

           [:h2 "Review and Selection Process"]
           [:p [:strong "Phase 1"] ": Applications are reviewed and selected by a committee of foundation staff with expertise in various program areas, and by curators Susan Meiselas and Stuart Alexander. In evaluating the work, we consider the quality of the photographs and their relevance to the Open Society Foundations' overall mission and activities. The committee also aims to select a diversity of issues and geographic areas in order to avoid repetition of topics shown in recent exhibitions. Past exhibitions can be viewed at: www.movingwalls.org."]
           [:p "We receive 200-400 submissions each round. For Moving Walls 20, we plan to select three to five bodies of work."]

           [:p [:strong "Phase 2"] ": Selected photographers will be designated wall space and encouraged to visit our office in New York to meet with our curators and prepare installation plans. Travel to our New York office for curatorial meetings is not part of Moving Walls payment and is not a requirement. Photographers who are unable to travel to New York may correspond with our curators by e-mail and submit their installation plans electronically. "]
           [:p "While curators work closely with photographers to determine an installation plan, final curatorial decisions are at the discretion of the Moving Walls curators and selection committee."]

           [:p "During this time, the selected photographers will be invited to apply for the Emerging Photographer Travel Grant."]

           [:h2 "Time Frame"]
           [:p [:strong "Thursday, April 19, 2012, 5pm EST"] " - APPLICATIONS DUE"]
           [:p "Early - late 2013 (nine months) - Moving Walls 20 in New York"]
           [:p "Late 2013 - mid 2014 (nine months) - Moving Walls 20 in Washington, DC"]

           [:h2 "Other Venues"]
           [:p "In the past, certain Moving Walls photographers have been asked to participate in subsequent exhibitions in Baltimore, Maryland (in conjunction with the Open Society Foundations office located there) and in New York City (at the Columbia University School of Social Work and the John Jay College of Criminal Justice). Should you be selected, the decision to participate would be entirely yours and a separate agreement would be executed."]

           [:h2 "Payment"]
           [:p "$2,000 royalty payment, plus production costs based on approved budget."]

           [:p "Selected photographers submit a budget application for printing, drymounting, and other production costs. Once the budget is approved, participants will be responsible for working within this budget and must use Open Society Foundations-approved labs. We will then pay for standard framing and window matting or back-mounting."]

           [:p "When the exhibition tour is completed, photographers will receive the framed and/or mounted work. The Open Society Foundations will cover the costs of returning work up to $750 for photographers based in the United States and $1250 internationally."]

           [:p "Emerging Moving Walls photographers can apply for additional travel grants to attend the opening in New York and to meet with local photo editors and NGOs. Applying for this grant is optional. Recipients will be determined at our discretion."]

           [:h2 "Licensing"]

           [:p "By participating in the exhibit, you grant the Open Society Foundations a nonexclusive, irrevocable, fully paid-up, royalty-free, and worldwide license in perpetuity to reproduce, distribute, publish, make derivative works from, and publicly display the work for purposes relating to the Open Society Foundations Documentary Photography Project, including, but not limited to, the following formats:"]
           [:ul
            [:li  "exhibition invitation"]
            [:li  "exhibition wall texts"]
            [:li  "exhibition catalog"]
            [:li  "educational or promotional material for the exhibition"]
            [:li  "Open Society Foundations' website or any successor or comparable medium of display"]]

           [:p "In addition, you grant to the Open Society Foundations a nonexclusive, irrevocable, fully paid-up, royalty-free, and worldwide license in perpetuity to reproduce and publicly display the work on our website, or any successor or comparable medium of display, solely for the Open Society Foundations non-commercial advocacy or educational purposes. In any and all uses, the Open Society Foundations shall provide a photographer's credit and identify it as related to Moving Walls 20."]

           [:h2 "Contact Information"]
           [:p "If you have any questions, please contact the Documentary Photography Project at (212) 547-6909 or " (link-to "mailto:docphoto@sorosny.org" "docphoto@sorosny.org") "."]

           [:h2 "Frequently Asked Questions"]
           [:h3 "Can I submit multiple entries?"]
           [:p "Yes, you are welcome to submit multiple entries. However, you will need to submit separate applications for each."]

           [:h3 "I am not a professional photographer, am I eligible to submit work?"]
           [:p "You do not need to be a professional photographer to apply. Submissions are reviewed based on the quality of the images; the coherence and storytelling capabilities of the project; and relevance of the topic for the Open Society Foundations. We place value first and foremost on the quality of the images, not the photographer's biography. Please note that the selection process is quite competitive (in spring 2011, we received approx. 400 submissions) and your work will be judged against that of professional photographers."]

           [:h3 "I am a curator/part of a photography collective. Do you accept submissions for group exhibitions?"]
           [:p "Group submissions are eligible. It is important to consider, however, that a group application will be considered for a space normally devoted to a single photographer's work. The edits will need to be quite tight in order to accommodate a group exhibition."]

           [:h3 "Do you accept photographic projects about the United States?"]
           [:p "Yes. The Open Society Foundations has extensive programming based in the United States. Please consult the US Programs website to get a sense of the priority issues: http://www.soros.org/initiatives/usprograms."]

           [:h3 "Is there work that you do not show?"]
           [:p "The work exhibited should explore issues in which the larger Open Society Foundations engages. Please take a look at our website (www.soros.org) for a better idea of what those issues are. There are some geographic regions in which we do not currently work, including Oceania, India, and Canada ; work from these countries will likely be considered out of scope. Themes that are out of scope include animal rights  and images of extreme poverty or homelessness without a social justice component ."]

           [:p "We do not show photographs that have been staged or manipulated, nor do we exhibit bodies of work documenting the activities of NGOs. Work made by journalists on a short-term assignment will be considered, but longer-term projects are strongly preferred."]

           [:h3 "My story focuses on a subject you featured last year, but approaches the topic in a different way. Will this be immediately disqualified?"]
           [:p "You are welcome to apply with a body of work related to a story that has been told in a previous Moving Walls exhibition. However, it is unlikely that it will be selected soon afterwards, unless the storytelling method and photographic style differ significantly."]

           [:h3 "Help, it's the day of the deadline and the application site appears to have crashed."]
           [:p "Due to high volume of submissions, we occasionally experience technical difficulties with the submission system on or approaching the day of the deadline. If this occurs, we ask that you please be patient and continue to check the site. If troubles persist, please send an email to docphoto@sorosny.org."]

           )})
