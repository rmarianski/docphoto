(ns docphoto.guidelines
  (:use [hiccup.page-helpers :only (link-to)]))

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
           [:p " For participating photographers, a key benefit of the program is to gain exposure for their projects, as well as the social justice or human rights issues they address. In addition to a $2500 honorarium, photographers receive their professionally-produced exhibitions at the end of the exhibition tour in NY and Washington, D.C."]
           [:p "With this next competition, Moving Walls will celebrate its 20th exhibition cycle. Moving Walls 20 will also be the inaugural exhibition in the Open Society Foundations' new headquarters on West 57th Street in New York. We intend to showcase three to five discrete bodies of work."]
           [:p "To view images from our recent exhibitions, please visit: " (link-to "http://www.movingwalls.org" "www.movingwalls.org") "."]
           [:h2 "Areas of Interest"]
           [:p "Each Moving Walls exhibit highlights issues or geographic regions where the Open Society Foundations are active. Priority is given to work whose subject has not been recently addressed in Moving Walls, and special consideration is given to long-term work produced over years of commitment to an issue or community. Work in progress may be submitted as long as a substantial portion of the work has been completed."]

           [:p "Listed below are some focus areas for the Open Society Foundations, and examples of specific topics about which we are interested in receiving submissions. " [:strong "Please note that photographers are welcome to submit their work for Moving Walls even if their subject area is not included on this list."] " All work submitted will be considered for exhibition. In addition to the focus areas listed below, please visit our website (" (link-to "http://www.soros.org/" "www.soros.org") ") for a complete listing of priorities and programs."]

           [:div.interest-areas
            (interest-list "Justice"
                           "Pretrial detention"
                           "Detention of immigrants")

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
                           "Economic downturn in the United States"
                           "Images that reframe mainstream media representations of African American men and boys")

            (interest-list "Women"
                           "Women in post-conflict countries")

            (interest-list "Youth"
                           "Youth movements, especially political participation in voter registration, policy reform efforts, public education, especially in Eastern Europe")

            (interest-list "Climate Change"
                           "The human costs of climate change")]

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
           [:p "Photographers must apply online at " (link "http://apply.movingwalls.org/exhibit/mw20/apply") "."]

           [:p "You will be asked to complete or upload the following:"]
           [:ol
            [:li [:strong "a project summary"] " (50 words maximum)"]
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
           [:p [:strong "Phase 1"] ": The entire body of 200-400 submissions are reviewed by Documentary Photography Project staff, who create a shortlist of applicants."]
           [:p [:strong "Phase 2"] ": Shortlisted applications are reviewed and selected by a committee of foundation staff with expertise in various program areas, and by curators Susan Meiselas and Stuart Alexander. In evaluating the work, we consider the quality of the photographs and their relevance to the Open Society Foundations’ overall mission and activities. The committee also aims to select a diversity of issues and geographic areas in order to avoid repetition of topics shown in recent exhibitions. Past exhibitions can be viewed at: " (link-to "http://www.movingwalls.org/" "www.movingwalls.org") "."]
           [:p "For Moving Walls 20, we plan to select three to five bodies of work."]
           [:p [:strong "Phase 3"] ": Selected photographers will be designated wall space and encouraged to visit our office in New York to meet with our curators and prepare installation plans. Travel to our New York office for curatorial meetings is not part of Moving Walls payment and is not a requirement. Photographers who are unable to travel to New York may correspond with our curators by e-mail and skype and submit their installation plans electronically."]
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
           [:p "You do not need to be a professional photographer to apply, however, submissions are reviewed based on the quality of the images. The coherence and storytelling capabilities of the project, and relevance of the topic for the Open Society Foundations, are also considered. We place value first and foremost on the quality of the images, not the photographer's biography. Please note that the selection process is quite competitive (in Spring 2011, we received approx. 400 submissions) and your work will be judged against that of professional photographers."]

           [:h3 "I am a curator/part of a photography collective. Do you accept submissions for group exhibitions?"]
           [:p "Group submissions are eligible. It is important to consider, however, that a group application will be considered for a space normally devoted to a single photographer's work. The edits will need to be quite tight in order to accommodate a group exhibition."]

           [:h3 "Do you accept photographic projects about the United States?"]
           [:p "Yes. The Open Society Foundations has extensive programming based in the United States. Please consult the US Programs website to get a sense of the priority issues: " (link "http://www.soros.org/initiatives/usprograms") "."]

           [:h3 "Is there work that you do not show?"]
           [:p "The work exhibited should explore issues in which the larger Open Society Foundations engages. Please take a look at our website (" (link-to "http://www.soros.org/" "www.soros.org") ") for a better idea of what those issues are. There are some geographic regions in which we do not currently work, including Oceania, India, and Canada ; work from these countries will likely be considered out of scope. Themes that are out of scope include animal rights  and images of extreme poverty or homelessness without a social justice component."]

           [:p "We do not show photographs that have been staged or manipulated, nor do we exhibit bodies of work documenting the activities of NGOs. Work made by journalists on a short-term assignment will be considered, but longer-term projects are strongly preferred."]

           [:h3 "My story focuses on a subject you featured last year, but approaches the topic in a different way. Will this be immediately disqualified?"]
           [:p "You are welcome to apply with a body of work related to a story that has been told in a previous Moving Walls exhibition. However, it is unlikely that it will be selected soon afterwards, unless the storytelling method and photographic style differ significantly."]

           [:h3 "Help, it's the day of the deadline and the application site appears to have crashed."]
           [:p "Due to high volume of submissions, we occasionally experience technical difficulties with the submission system on or approaching the day of the deadline. If this occurs, we ask that you please be patient and continue to check the site. If troubles persist, please send an email to " (link-to "mailto:docphoto@sorosny.org" "docphoto@sorosny.org") "."]

           )
   :prodgrant2012 (list
                   [:p {:style "margin-bottom: 4em"}
                    "Once you have read the guidelines, you can "
                    (link-to "http://docphoto.soros.org/exhibit/prodgrant2012/apply" "apply")]
                   [:div {:style "text-align: center"}
                    [:h1 "GUIDELINES – 2012 COMPETITION"]
                    [:h1 "Grant Opportunity for Photographers From Central Asia, the South Caucasus, Afghanistan, Mongolia, and Pakistan"]
                    [:hr {:style "margin: 2em 0"}]
                    [:p {:style "font-weight: bold"} "Deadline: May 10, 2012 at 5pm EST"]
                    [:hr {:style "margin: 2em 0"}]]
                   [:p "The Open Society Documentary Photography Project and the Arts and Culture Program are offering grants for documentary photographers from Central Asia, the South Caucasus, Afghanistan, Mongolia, and Pakistan. With these grants, we support visual documentation of important human rights and social issues in the region and provide training and mentorship to local photographers."]
                   [:p "We will award approximately ten cash stipends in the amount of $3,500 USD each to photographers to produce a photo essay on a critical human rights or social issue in the region. Along with the stipend, successful applicants will receive two master-level workshops (past workshops were held in Istanbul and Tbilisi) on visual storytelling through photography and multimedia. These workshops are led by internationally-recognized photographers and industry professionals who will provide ongoing mentorship throughout the six-month grant term."]
                   [:p "This grant is intended for photographers who are committed to pursuing a career in photography and have prior technical expertise and/or training.  Details about past recipients of this grant and their projects can be found on our web site here:"]
                   [:p (link "http://www.soros.org/initiatives/photography/focus_areas/production-individual/grantees")]
                   [:h2 "Details &amp; Information"]
                   [:p "The six-month grant program will provide up to 10 regionally based photographers with: "]
                   [:ul
                    [:li "A cash stipend;"]
                    [:li "Audio equipment and software;"]
                    [:li "Two advanced training workshops;"]
                    [:li "Ongoing coaching and mentoring throughout the grant period;"]
                    [:li "Deadlines to keep your project on track;"]
                    [:li "Publicity and promotion of the work produced; and"]
                    [:li "Opportunities for follow-up support to distribute the finished work;"]]
                   (defined-item "Grant Term" ": The grant will begin in November 2012 and end in June 2013.")
                   (defined-item "Grant" ": Approximately 10 cash stipends in the amount of $3,500 each, plus audio equipment and software purchased by Open Society Foundations will be awarded to photographers to assist in the production of a discrete body of work on a proposed topic.  The cash stipend can be used for project expenses, new equipment, film and developing costs, the photographer’s time to work on the project, etc.")
                   (defined-item "Mentorship" ": Grantees will be assigned an internationally recognized photographer who will serve as a mentor throughout the grant period.  Past mentors include Thomas Dworzak, Yuri Kozyrev, and Antonin Kratochvil. During the six-month period, grantees will be asked to upload images every month and discuss them with their mentors.")
                   (defined-item "Workshops" ": Mentors and grantees will participate in two workshops. The first is a seven-day workshop and will take place in November 2012. It will consist of discussion of proposed projects, portfolio review, shooting and editing exercises, and audio and multimedia training. Instructor Bob Sacha has led the audio and multimedia workshop in the past. The second workshop will be held over five days in June 2013. We will ask grantees to prepare a final edit of their projects. They will receive guidance from their mentors and work alongside internationally recognized picture and multimedia editors to explore options for continuing their projects and distributing their work. Past editors include MaryAnne Golon, Andrei Polikanov, Francesca Sears, and Chad Stevens.")
                   [:p "Workshop location will be confirmed approximately 2 months prior to each workshop. Past workshops were held in Istanbul, Turkey and Tbilisi, Georgia. Language translation for Russian and Mongolian speakers will be provided if necessary. Translation from other languages will not be available."]
                   [:p "The Open Society Foundations will pay travel and hotel expenses and provide a per diem to cover meals and incidentals for the workshops."]
                   (defined-item "Collaboration" ": Cooperation with other Open Society Foundations programs is encouraged.")
                   (defined-item "Publicity and Promotion" ": The Open Society Foundations will publish finished grantee projects on our web site, promote the projects online via Facebook and Twitter, and facilitate relationships with photo editors, curators, and festival and exhibition organizers to help bring the work to broader audiences (there are no guarantees about publication).")
                   (defined-item "Rights to Work and Licensing" ": By participating in the grant program, you grant the Open Society Foundations a non-exclusive, royalty-free, irrevocable, perpetual, sublicensable, and worldwide license to the images you create pursuant to the grant (“Portfolio Images”) for the following:")
                   [:ul
                    [:li [:strong "Web and exhibition use, in perpetuity"] ": to publish, distribute, and make derivative works from your Portfolio Images on the internet, including the Open Society Foundations-related websites and in Open Society Foundations-related traveling exhibitions."]
                    [:li [:strong "Print rights, for two years"] ": to publish and distribute the Portfolio Images in any Open Society Foundations print publication for a period of two years after the grant period is complete."]
                    [:li [:strong "Distribution"] ": Upon completion of the project, grantees will have the opportunity to apply for additional support to distribute the finished work, including funds for exhibition, publication, advocacy-based projects, and creating visual resources."]]

                   (defined-item "Selection Process" ": Open Society Foundations staff will review the applications and select a group of finalists.  Finalist proposals will then be carefully reviewed by the master photographer mentors, plus a jury of Open Society Foundations staff and outside experts familiar with the region.  Applicants will be judged on the strength of their images, their potential for professional growth, and the relevance of their proposed subject to Open Society Foundations work.")
                   [:h2 "Areas of Interest"]
                   [:p "Proposals should address a specific problem of social justice or human rights in one or more of the eligible countries.  Listed below are topics of interest to the Open Society Foundations. " [:strong "Please note that applicants are welcome to submit a proposal on a topic not included on this list."]]
                   [:ul
                    (let [topics
                          ["Women’s human rights;"
                           "Sexual and reproductive health and rights;"
                           "Ethnic minorities;"
                           "Migration, including labor migrants, migrant detention, returned migrants, border controls, migrant children and children left behind, labor migration from CA republics to Russia;"
                           "LGBTI (lesbian, gay, bisexual, transgender, intersex) rights;"
                           "Statelessness and citizenship;"
                           "Pre-trial detention, including ill-treatment in custody;"
                           "War crimes and crimes against humanity;"
                           "Religious freedom;"
                           "Climate change and environmental challenges;"
                           "Urban renewal and transformation"
                           "Public health issues including but not limited to tuberculosis, HIV, AIDS, Hepatitis C, and access to essential medicines;"
                           "Palliative care;"
                           "Drug policy and narcotics;"
                           "Resource development and exploitation;"
                           "Violence against women, including harmful traditional practices;"
                           "Regional and ethnic integration;"
                           "Youth activism; and"
                           "Disability rights/equality and inclusion of people with disabilities."
                           ]]
                      (for [topic topics] [:li topic]))]
                   [:p "Grants may be used to begin a new project that can be completed in the six-month timeframe of the grant or to complete work on an existing project. Projects should explore an issue in-depth, over an extended period of time. Photographers will be expected to work on the project consistently over the course of the six-month grant term."]
                   [:p "For a summary of topics that have been awarded grants in 2009-2011, please see here:" [:br]
                    (link "http://www.soros.org/initiatives/photography/focus_areas/production-individual/grantees")]
                   [:h2 "Eligibility"]
                   [:p "The competition is open to photographers from the following countries:  Afghanistan, Armenia, Azerbaijan, Georgia, Kazakhstan, Kyrgyzstan, Mongolia, Pakistan, Tajikistan, Turkmenistan, and Uzbekistan."]
                   [:p "Applicants must currently reside in their home country. Exceptions will be made for applicants from Turkmenistan and Uzbekistan living outside their home country."]
                   [:p "Applicants from other countries may also be eligible if they can demonstrate a long-term commitment to one of the designated countries (for example, by having lived and worked in one of the countries for many years)."]
                   [:p "Professional and emerging photographers are eligible to apply. Photographers who have not specialized in documentary photography will be considered as long as the proposed work is documentary in nature."]
                   [:p "Technical familiarity with photography is required.  Journalists or activists who have not had experience with photography are not eligible to apply."]
                   [:p "Applicants must speak English or Russian."]
                   [:p "Participants must be able to attend both workshops (in November 2012 and June 2013) and commit themselves to working and communicating consistently over the six months of the grant term."]
                   [:p "Collaborative projects will be considered and applicants from different countries may apply together (in which case each photographer will receive a $3,500 grant)."]
                   [:h2 "History and Background"]
                   [:p "The Open Society Documentary Photography Project and the Arts and Culture Program created the grant in 2009 to support photographers from the region who contribute to civil society by critically exploring current social problems. The focus of the grant and training program is to encourage long-form documentary storytelling that explores issues in-depth and over time, rather than spot news photography.  The program promotes personal and professional growth through guided and personalized feedback, project assistance, and professional education. We also recognize the lack of affordable, advanced training programs for photographers in the region. This grant program aims to help locally-based photographers compete in international markets."]
                   [:p "We are also committed to furthering public dialogue around issues relevant to the work of the Open Society Foundations. As such, proposals will be carefully judged by Open Society Foundations staff and outside advisors to ensure that the issues are urgent and timely."]
                   [:h2 "Application Instructions"]
                   [:p "Too apply, please go to: " (link "http://docphoto.soros.org/exhibit/prodgrant2012/apply")]
                   [:h2 "Deadline"]
                   [:p "The deadline for applying is " [:strong "May 10, 2012 at 5pm EST."]]
                   [:h2 "Contact Information"]
                   [:p "Please write to " (link-to "mailto:docphoto@sorosny.org" "docphoto@sorosny.org") " with any questions you have about the program."]
                   [:div {:style "text-align: center; margin-top: 8em"}
                    [:h1 "OPEN SOCIETY DOCUMENTARY PHOTOGRAPHY PROJECT AND
ARTS AND CULTURE NETWORK PROGRAM"]
                    [:h1 "Grant for Photographers From Central Asia, the South Caucasus, Afghanistan, Mongolia, and Pakistan"]
                    [:h2 {:style "margin: 3em 0"} "Application Form and Instructions"]]
                   [:h2 "DEADLINE:  May 10, 2012 at 5pm EST"]
                   [:h2 "APPLICATION: To apply on-line, please go to: " (link "http://docphoto.soros.org/exhibit/prodgrant2012/apply")]
                   [:h2 "Contact Information and Project Summary:"]
                   [:p [:strong "This section must be filled out in English, including one sentence summarizing your project."]]
                   (let [fields
                         ["Last Name:"
                          "First Name:"
                          "Street:"
                          "City:"
                          "State:"
                          "Country:"
                          "Postal Code:"
                          "Email Address:"
                          "Phone Number:"
                          "Project Summary:"]]
                     [:ul
                      (for [field fields] [:li field])])
                   [:h2 "Additional Proposal Requirements:"]
                   [:p [:strong "Please submit all application texts in English or Russian."]]
                   (defined-item "Proposal Narrative" ": Please provide a three-page description of the proposed project, a summary of the issue and its importance, a description of the plan for producing the work, a description of sources and contacts for the project, and thoughts on how the finished product might be distributed.")
                   (defined-item "Personal Statement" ": Please provide a one-page summary of your experience as a photographer, the training you have received, and why you feel this grant program would be useful for you now.")
                   (defined-item "Photographs" ": Please provide 20-30 examples of your photography (up to 5 MB each image) with captions in English or Russian.   The judges will be getting a sense of how you will approach your proposed subject.  We suggest including images that include any combination of the following:")
                   (let [images
                         ["a current project you are working on"
                          "a project you have completed"
                          "images that you feel best represent your vision as a photographer"
                          "anything else that might suggest what your final set of images might look like."]]
                     [:ul
                      (for [image images] [:li image])])
                   [:p "If you don’t have images that meet these suggestions or if you are proposing to try a new direction in your work, you may also include a brief statement that explains your ideas and the directions you are considering."]
                   [:h2 "Application Instructions If You Are Applying Online:"]
                   [:p "To apply on-line, please go to: " (link "http://docphoto.soros.org/exhibit/prodgrant2012/apply")]
                   [:p "The \"Contact Information and Project Summary\" section should be completed in English. The proposal statements can be entered into a text box in English or Russian. The photographs should be uploaded as low-res jpegs with captions. Any additional text or other material can be uploaded in English or Russian as a Word document."]
                   [:p "Application deadline is May 10, 2012 at 5pm EST."]
                   [:p "If you are unable to apply online, please write to " (link-to "mailto:docphoto@sorosny.org" "docphoto@sorosny.org") " to make alternate arrangements."]
                   [:p [:strong "*NOTE: Applications may be submitted in English or Russian only."]]
                   )})
