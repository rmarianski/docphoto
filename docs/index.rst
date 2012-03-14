=====
Index
=====



Overview
========

The Docphoto project holds competitions where applicants submit their
body of work, they are reviewed, and then winners are selected. This
represents a portal site where users can submit their work for
consideration.


Storage
=======

Applicant data is stored in a salesforce instance. The images
themselves are stored separately due to space considerations. This
portal application stores it directly on the filesystem. However, from
a system perspective, the storage strategy is incidental. The key
aspect of it is that the portal application needs to be able to link
these images with the application data in salesforce somehow. The
filesystem structure that this portal application uses is keyed off of
the id of the application itself, which is how the link is made.



Process
=======

The process runs through a few stages.

1. Application
   Users submit their applications, up until a deadline.

2. Internal Review
   The internal team reviews all applications, and does a basic
   elimination to remove appliactions that are out of scope, not
   relevant, or unlikely to be selected.

3. Vetting
   Other subject matter experts are invited to review particular
   applications that are relevant to their domain of knowledge. The
   internal team manages who reviews which applications.

4. Selection Committee
   A selection committee reviews all remaining applications and
   selects the winners.

5. Award Announcement
   An email is sent out to notify the winners.


Technical Support for Workflow
==============================

To manage the process workflow outlined above, the applications in
salesforce have a status field, which indicates what phase of the
pipeline the application is in.


Salesforce Objects
==================

To store the application data, most of it is stored in custom
objects. However, when a user registers, a contact object is created
for that user.

* Contact -> Portal users
* Exhibit -> Represents metadata about the particular competition.
* Exhibit Application -> The application fields themselves are stored
  here. They are links with a particular exhibit.
* Image -> Stores metadata about the particular images. Of note, an
  order is associated with a particular image, to display an
  appropriate ordering of images for review. Linked to an application.
* Exhibit Application Review -> Stores the review information. Linked
  with an application.
* Exhibit Review Request -> Allows a user to review an
  application. Useful for the vetting/selection committee
  reviews. Linked with an application and a contact.



User Registration
=================

Basic contact information is captured here.

* Username
* Password
* First name
* Last name
* Email
* Phone number
* Address
* Email subscription?

The email subscription is a setting to express whether the user would
like to remain informed of other Docphoto announcements.


Application Fields
==================

The fields vary from competition to competition. Generally speaking
though, they fit this theme:

* Project title
* Short summary
* Bio
* Longer narrative about project
* Focus region (useful for management purposes internal team)


Images
======

Applicants are asked to upload around 15-20 images. The size limit for
each image is 5 megs. Applicants must be able to upload multiple
images simultaneously. Thumbnails are automatically created for these
images. One is a smaller thumbnail to display in lists, and another is
a large thumbnail that is used for review. High quality images aren't
needed for the purposes of the portal. The winners are later asked for
higher quality images if necessary.


Reviews
=======

The 2 main pieces to a review are the comments and the rating. The
ratings are used to get a rough feel for where the application
stands. But as the application proceeds to the final stages of the
process, the comments carry much more weight. An application with a
lower average score but better comments may be chosen over an
application with a higher level score.

A review stage field also exists to capture which phase the review
falls in.


Exhibit Review Requests
=======================

These objects represent an invitation to review an application. During
the vetting and committee selection phases, this is how applications
will be assigned to reviewers. Essentially, these are junction objects
that refer to the application and the contact, or reviewer. Another
bit of information stored on these is the review stage associated with
the forthcoming review. This will drive what review stage gets
populated on the review object.
