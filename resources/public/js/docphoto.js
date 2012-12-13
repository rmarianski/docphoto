goog.provide('docphoto');
goog.provide('docphoto.editor');

goog.require('goog.array');
goog.require('goog.dom');
goog.require('goog.dom.TagName');
goog.require('goog.dom.classes');
goog.require('goog.events');
goog.require('goog.events.EventType');
goog.require('goog.fx.DragListDirection');
goog.require('goog.fx.DragListGroup');
goog.require('goog.fx.dom');
goog.require('goog.net.EventType');
goog.require('goog.net.XhrIo');
goog.require('goog.object');
goog.require('goog.string');

goog.require('goog.editor.Command');
goog.require('goog.editor.Field');
goog.require('goog.editor.SeamlessField');
goog.require('goog.editor.plugins.BasicTextFormatter');
goog.require('goog.editor.plugins.EnterHandler');
goog.require('goog.editor.plugins.HeaderFormatter');
goog.require('goog.editor.plugins.ListTabHandler');
goog.require('goog.editor.plugins.RemoveFormatting');
goog.require('goog.editor.plugins.SpacesTabHandler');
goog.require('goog.editor.plugins.UndoRedo');
goog.require('goog.ui.editor.DefaultToolbar');
goog.require('goog.ui.editor.ToolbarController');

// to make google advanced mode happy
goog.require('goog.debug.ErrorHandler');
goog.require('goog.Uri');


docphoto.errorClass = 'error';

/**
 * @param {!string} containerId
 * @param {!string} pickFilesId
 * @param {!string} uploadId
 * @param {!string} filesListId
 * @param {!string} imagesId
 * @param {!string} imagesDescription
 * @param {!string} numImagesErrorId
 * @param {Object} options
 * @constructor
 */
docphoto.Uploader = function(containerId, pickFilesId, uploadId,
                      filesListId, imagesId, imagesDescription,
                      numImagesErrorId, options) {
  this.extensions = "jpg,gif,png";
  var defaultOptions = {
    'runtimes': 'gears,html5,flash,silverlight,browserplus',
    'browse_button': pickFilesId,
    'container': containerId,
    'max_file_size': '10mb',
    'flash_swf_url': '/public/js/plupload/js/plupload.flash.swf',
    'silverlight_xap_url': '/public/js/plupload/js/plupload.silverlight.xap',
    'filters': [
      {'title': "Image files", 'extensions': this.extensions}
    ]
  };
  var uploadOptions = {};
  if (goog.isObject(options)) {
    goog.object.extend(uploadOptions, defaultOptions, options);
  }
  this.uploader = new plupload.Uploader(uploadOptions);

  this.filesList = goog.dom.getElement(filesListId);
  this.imagesId = imagesId;
  this.images = goog.dom.getElement(imagesId);
  this.imagesDescription = goog.dom.getElement(imagesDescription);

  this.uploadLink = goog.dom.getElement(uploadId);
  this.uploadContainer = /** @type {!Element} */ this.uploadLink.parentNode;
  goog.events.listen(this.uploadLink, goog.events.EventType.CLICK,
                     this.onUpload, false, this);

  goog.events.listen(this.images, goog.events.EventType.CLICK,
                     this.onImageDelete, false, this);

  this.numImagesError = goog.dom.getElement(numImagesErrorId);

  this.submitButton = goog.dom.getNextElementSibling(this.numImagesError);
  goog.events.listen(this.submitButton, goog.events.EventType.CLICK,
                     this.onImagesSave, false, this);

  this.filesToUpload = 0;
  this.captions = {};
  this.captionRequiredText = options.captionRequiredText || 'Caption required';

  if (this.haveImages_()) {
    this.imagesDescription.style.display = 'block';
    var elts = goog.dom.getElementsByTagNameAndClass(
      goog.dom.TagName.TEXTAREA, undefined, this.images);
    goog.array.forEach(elts, function(textarea) {
      var id = goog.getUid(textarea);
      this.captions[id] = textarea;
    }, this);
    this.adjustNumImagesError();
  } else {
    this.submitButton.disabled = 'disabled';
  }

  this.dlg = null;
  this.initializeDragDrop();

  this.uploader.bind('Init', goog.bind(this.onInit, this));
  this.uploader.init();
  this.uploader.bind('FilesAdded', goog.bind(this.onFilesAdded, this));
  this.uploader.bind('UploadProgress', goog.bind(this.onUploadProgress, this));
  this.uploader.bind('Error', goog.bind(this.onUploadError, this));
  this.uploader.bind('FileUploaded', goog.bind(this.onUploadDone, this));

};

/**
 * custom class exists just to override cloneNode_
 * @extends {goog.fx.DragListGroup}
 * @constructor
 */
docphoto.DragListGroup = function() {
  goog.base(this);
};
goog.inherits(docphoto.DragListGroup, goog.fx.DragListGroup);


docphoto.Uploader.prototype.initializeDragDrop = function() {
  if (goog.isDefAndNotNull(this.dlg)) {
    goog.dispose(this.dlg);
  }
  //this.dlg = new goog.fx.DragListGroup();
  this.dlg = new docphoto.DragListGroup();
  this.dlg.addDragList(this.images, goog.fx.DragListDirection.DOWN);
  this.dlg.setHysteresis(5);
  this.dlg.setDragItemHandleHoverClass('draggable');
  this.dlg.setDraggerElClass('dragging');
  this.dlg.setFunctionToGetHandleForDragItem(docphoto.findDragElement);

  // ondrag handler not needed any more
  // goog.events.listen(this.dlg, goog.fx.DragListGroup.EventType.DRAGEND,
  //                    this.onDrag, false, this);
  this.dlg.init();
};

/**
 * return the image element associated with the drag list item
 * @param {Element} dragItem
 * @return {Element}
 */
docphoto.findDragElement = function(dragItem) {
  if (goog.isDefAndNotNull(dragItem)) {
    var imageContainer = goog.dom.getElementsByTagNameAndClass(
      goog.dom.TagName.DIV, 'image-container', dragItem)[0];
    if (goog.isDefAndNotNull(imageContainer)) {
      var image = goog.dom.getFirstElementChild(imageContainer);
      return image;
    }
  }
  return null;
}

/**
 * @param {Object} up
 * @param {Object} params
 */
docphoto.Uploader.prototype.onInit = function(up, params) {
  goog.dom.removeChildren(this.filesList);
};

/**
 * @param {!Event} event
 */
docphoto.Uploader.prototype.onUpload = function(event) {
  event.preventDefault();
  this.uploadContainer.style.display = 'none';
  this.uploader.start();
};

/**
 * @param {Object} up
 * @param {Array.<Object>} files
 */
docphoto.Uploader.prototype.onFilesAdded = function(up, files) {
  var filesList = this.filesList;
  var n = 0;
  goog.array.forEach(files, function(file) {
    var name = goog.dom.createDom(
      goog.dom.TagName.SPAN, undefined, file.name);
    var percent = goog.dom.createDom(
      goog.dom.TagName.SPAN, {'class': 'percent'}, '0%');
    var node = goog.dom.createDom(
      goog.dom.TagName.P, {'id': file.id}, name, ' - ', percent);
    goog.dom.appendChild(filesList, node);
    n += 1;
  });
  this.filesToUpload += n;
  this.uploadContainer.style.display = 'block';
  docphoto.fadeIn(this.uploadContainer);
};

/**
 * @param {Object} file
 * @param {string} string
 */
docphoto.Uploader.prototype.updateFilePercentage = function(file, string) {
  if (file.id) {
    var node = goog.dom.getElement(file.id);
    if (goog.isDefAndNotNull(node)) {
      var percent = goog.dom.getElementByClass('percent', node);
      if (goog.isDefAndNotNull(percent)) {
        percent.innerHTML = string;
      }
    }
  }
};

/**
 * @param {Object} up
 * @param {{percent: string}} file
 */
docphoto.Uploader.prototype.onUploadProgress = function(up, file) {
  this.updateFilePercentage(file, file['percent'] + '%');
};

docphoto.Uploader.prototype.fileUploaded_ = function() {
  this.filesToUpload -= 1;
  if (this.filesToUpload === 0) {
    this.initializeDragDrop();
    if (this.haveImages_()) {
      this.imagesDescription.style.display = 'block';
      docphoto.fadeIn(this.imagesDescription);
    }
    this.adjustNumImagesError();
  }
};

docphoto.Uploader.prototype.adjustNumImagesError = function() {
  var nImages = this.countImages_();
  if (nImages >= 15 && nImages <= 20) {
    this.numImagesError.style.display = 'none';
    this.submitButton.removeAttribute('disabled');
  } else {
    this.numImagesError.style.display = 'block';
    this.submitButton.disabled = 'disabled';
  }
};

/**
 * @return boolean
 */
docphoto.Uploader.prototype.haveImages_ = function() {
  return this.countImages_() > 0;
};

/**
 * @return {!number}
 */
docphoto.Uploader.prototype.countImages_ = function() {
  return goog.dom.getChildren(this.images).length;
};

/**
 * helper function to fade an element in
 *
 * @param {Element} el
 * @param {number=} duration
 */
docphoto.fadeIn = function(el, duration) {
  var anim = new goog.fx.dom.FadeInAndShow(el, duration || 1000);
  anim.play();
};

/**
 * @param {Object} up
 * @param {Object} file
 * @param {Object} responseObject
 */
docphoto.Uploader.prototype.onUploadDone = function(up, file, responseObject) {
  var imageHtml = responseObject.response;
  var li = goog.dom.createElement(goog.dom.TagName.LI);
  li.innerHTML = imageHtml;

  goog.dom.appendChild(this.images, li);
  docphoto.fadeIn(li);

  var textarea = goog.dom.getElementsByTagNameAndClass(
    goog.dom.TagName.TEXTAREA, undefined, li)[0];
  if (goog.isDefAndNotNull(textarea)) {
    var id = goog.getUid(textarea);
    this.captions[id] = textarea;
  }

  this.updateFilePercentage(file, '100%');

  this.fileUploaded_();
};

/**
 * @param {Object} up
 * @param {Object} error
 */
docphoto.Uploader.prototype.onUploadError = function(up, error) {
  if (error.code === plupload.FILE_EXTENSION_ERROR) {
    // when a file with an invalid extension is added
    var errMsg = ('Not adding: ' + error.file.name +
                  '. File must end in: ' + this.extensions);
    var errorNode = goog.dom.createDom(
      goog.dom.TagName.P, undefined, errMsg);
    goog.dom.appendChild(this.filesList, errorNode);
  } else if (error.code === plupload.HTTP_ERROR) {
    var file = error.file;
    if (goog.isDefAndNotNull(file)) {
      this.updateFilePercentage(file, "Error uploading file.");
    }
    this.fileUploaded_();
  } else {
    var file = error.file;
    var msg = 'Error: ' + error.code + ' - ' + error.message;
    if (goog.isDefAndNotNull(file)) {
      this.updateFilePercentage(file, msg);
    } else {
      var node = goog.dom.createDom(
        goog.dom.TagName.P, undefined, msg);
      goog.dom.appendChild(this.filesList, node);
    }
    this.fileUploaded_();
  }
};

/**
 * @param {!Event} event
 */
docphoto.Uploader.prototype.onImageDelete = function(event) {
  var target = /** @type {!Element} */ event.target;
  if (goog.dom.classes.has(target, 'image-delete')) {
    event.preventDefault();
    var el = target;
    while (el !== null) {
      if (goog.dom.classes.has(el, 'image-container')) {
        var image = goog.dom.getFirstElementChild(el);
        if (goog.isDefAndNotNull(image)) {
          var li = /** @type {!Element} */ el.parentNode;
          var textarea = goog.dom.getElementsByTagNameAndClass(
            goog.dom.TagName.TEXTAREA, undefined, li)[0];
          if (goog.isDefAndNotNull(textarea)) {
            var id = goog.getUid(textarea);
            delete this.captions[id];
          }
          goog.dom.removeNode(li);
          this.adjustNumImagesError();
        }
        break;
      }
      el = goog.dom.getPreviousElementSibling(el);
    }
  }
  if (!this.haveImages_()) {
    this.imagesDescription.style.display = 'none';
  }
};

/**
 * @param {!Event} event
 */
docphoto.Uploader.prototype.onImagesSave = function(event) {
  // check to see that all captions have something set
  // and if not, flag an error and prevent form submission

  var containsErrors = false;
  var error = null;
  var focusElt = null; // focus in on this error element
  goog.object.forEach(this.captions, function(textarea, id) {
    var li = textarea.parentNode;
    error = goog.dom.getElementsByTagNameAndClass(
      goog.dom.TagName.P, docphoto.errorClass, li)[0];
    if (goog.isDefAndNotNull(error)) {
      goog.dom.removeNode(error);
    }
    if (goog.string.isEmpty(textarea.value)) {
      error = goog.dom.createDom(goog.dom.TagName.P,
                                 docphoto.errorClass,
                                 this.captionRequiredText);
      goog.dom.insertChildAt(li, error, 0);
      focusElt = textarea;
      containsErrors = true;
    }
  }, this);
  if (containsErrors) {
    event.preventDefault();
    if (goog.isDefAndNotNull(focusElt)) {
      focusElt.focus();
    }
  }
};

/**
 * @param {!Element} imageEl
 * @return {!string}
 */
docphoto.parseImageId_ = function(imageEl) {
  var src = imageEl.getAttribute('src');
  var fields = src.split('/');
  var imageId = fields[2];
  return imageId;
};

// not needed any more
// ordering is handled through order of inputs on page
// /**
//  * @param {!Event} event
//  */
// docphoto.Uploader.prototype.onDrag = function(event) {
//   // var orderings = goog.dom.getElementsByTagNameAndClass(
//   //   goog.dom.TagName.INPUT, 'image-order', this.images);
//   // goog.array.forEach(orderings, function(ordering, i) {
//   //   ordering.value = i+1;
//   // });
// };

/**
 * @param {!string} maxWordsErrMsg
 */
docphoto.editor.triggerEditors = function(maxWordsErrMsg) {
  var textareas = goog.dom.getElementsByTagNameAndClass(
    goog.dom.TagName.TEXTAREA, 'editor');
  goog.array.forEach(textareas,
                     goog.partial(docphoto.editor.makeEditor,
                                  maxWordsErrMsg));
};

/**
 * @param {!Element} textarea
 */
docphoto.editor.makeEditor = function(maxWordsErrMsg, textarea) {
  var id = textarea.getAttribute('id');
  var classes = goog.dom.classes.get(textarea);
  var maxWords = null;
  goog.array.forEach(classes, function(cls) {
    if (cls.indexOf('max-') === 0) {
      var maxValue = cls.substr(4);
      maxWords = parseInt(maxValue, 10);
      if (isNaN(maxWords)) {
        maxWords = null;
      }
    }
  });

  var field = new goog.editor.Field(id);

  field.registerPlugin(new goog.editor.plugins.BasicTextFormatter());
  field.registerPlugin(new goog.editor.plugins.RemoveFormatting());
  field.registerPlugin(new goog.editor.plugins.UndoRedo());
  field.registerPlugin(new goog.editor.plugins.ListTabHandler());
  field.registerPlugin(new goog.editor.plugins.SpacesTabHandler());
  field.registerPlugin(new goog.editor.plugins.EnterHandler());
  field.registerPlugin(new goog.editor.plugins.HeaderFormatter());

  // Specify the buttons to add to the toolbar, using built in default buttons.
  var buttons = [
    goog.editor.Command.BOLD,
    goog.editor.Command.ITALIC,
    goog.editor.Command.UNDERLINE,
    goog.editor.Command.FONT_COLOR,
    goog.editor.Command.BACKGROUND_COLOR,
    goog.editor.Command.REMOVE_FORMAT,
    goog.editor.Command.FONT_FACE,
    goog.editor.Command.FONT_SIZE,
    goog.editor.Command.UNDO,
    goog.editor.Command.REDO,
    goog.editor.Command.UNORDERED_LIST,
    goog.editor.Command.ORDERED_LIST,
    goog.editor.Command.INDENT,
    goog.editor.Command.OUTDENT,
    goog.editor.Command.JUSTIFY_LEFT,
    goog.editor.Command.JUSTIFY_CENTER,
    goog.editor.Command.JUSTIFY_RIGHT
  ];

  var toolbarElement = goog.dom.createDom(goog.dom.TagName.DIV,
                                          {"id": id + '-toolbar',
                                           "class": "editor"});
  goog.dom.insertSiblingBefore(toolbarElement, textarea);
  var toolbar = goog.ui.editor.DefaultToolbar.makeToolbar(buttons,
                                                          toolbarElement);

  // Hook the toolbar into the field.
  var toolbarController =
      new goog.ui.editor.ToolbarController(field, toolbar);

  // store the data in a hidden field
  var dataField = goog.dom.createDom(goog.dom.TagName.INPUT,
                                     {"type": "hidden",
                                      "name": textarea.getAttribute('name')});
  dataField.value = textarea.value;
  goog.dom.insertSiblingBefore(dataField, textarea);

  var errFn = null;
  var clearErrFn = null;
  if (goog.isDefAndNotNull(maxWords)) {
    errFn = goog.partial(docphoto.editor.maxWordsError,
                         dataField, maxWords, maxWordsErrMsg);
    clearErrFn = goog.partial(docphoto.editor.maxWordsClearError, dataField);
  }

  goog.events.listen(field, goog.editor.Field.EventType.DELAYEDCHANGE,
                     goog.partial(docphoto.editor.updateFieldContents_,
                                  dataField, field, maxWords, errFn, clearErrFn));

  field.makeEditable();

  if (goog.isDefAndNotNull(dataField.value)) {
    field.setHtml(/* addParagras */ false,
                  /* html */ dataField.value,
                  /* don't fire a change event */ true,
                  /* apply lorem ipsum styles */ false);
  }
};

/**
 * @param {!Element} dataField
 * @param {!Object} editorField
 * @param {?number} maxWords
 * @param {?function(!number)} errFn
 * @param {?function()} clearErrFn
 */
docphoto.editor.updateFieldContents_ = function(dataField, editorField, maxWords,
                                         errFn, clearErrFn) {
  var contents = editorField.getCleanContents();
  dataField.value = contents;

  if (goog.isDefAndNotNull(maxWords)) {
    var text = docphoto.editor.convertHtmlToText(contents);
    var wordCount = docphoto.editor.countWords(text);
    if (wordCount > maxWords) {
      errFn(wordCount);
    } else {
      clearErrFn();
    }
  }
};


/**
 * @param {!Element} field
 * @param {number} maxWords
 * @param {string} errMsg
 * @param {number} wordCount
*/
docphoto.editor.maxWordsError = function(field, maxWords, errMsg, wordCount) {
  // display error message next to text area
  var errorField = docphoto.editor.findErrorFieldFromDataField(field);
  if (!goog.isDefAndNotNull(errorField)) {
    errorField = goog.dom.createDom(goog.dom.TagName.DIV,
                                    docphoto.errorClass);
  }
  errMsg = errMsg + wordCount;
  errorField.innerHTML = errMsg;
  field.parentNode.appendChild(errorField);

  // disable submit button
  var submit = docphoto.editor.findSubmitButton(field);
  submit.disabled = 'disabled';
};

/**
 * @param {!Element} field
 * @return {?Element}
*/
docphoto.editor.findErrorFieldFromDataField = function(field) {
  var parent = field.parentNode;
  var errorField = null;
  var errorElts = parent.getElementsByClassName(docphoto.errorClass);
  return errorElts.length > 0 ? errorElts[0] : null;
};

/**
 * @param {!Element} field
*/
docphoto.editor.maxWordsClearError = function(field) {
  var errorField = docphoto.editor.findErrorFieldFromDataField(field);
  if (goog.isDefAndNotNull(errorField)) {
    goog.dom.removeNode(errorField);
  }

  // enable submit button
  var submit = docphoto.editor.findSubmitButton(field);
  submit.removeAttribute('disabled');
};

/**
 * @param {!Element} field
*/
docphoto.editor.findSubmitButton = function(field) {
  var elt = field;
  while (true) {
    elt = elt.parentNode;
    if (!goog.isDefAndNotNull(elt) || elt.nodeName === goog.dom.TagName.FORM) {
      break;
    }
  }
  return goog.dom.getLastElementChild(elt);
};

/**
 * @param {!string} html
 * @return {!string}
 */
docphoto.editor.convertHtmlToText = function(html) {
  // set the html on a dom element, and then grab the text from there
  var dummyNode = goog.dom.createElement(goog.dom.TagName.DIV);
  dummyNode.innerHTML = html;
  var text = goog.dom.getTextContent(dummyNode);
  return text;
};

/**
 * @param {!string} text
 * @return {number}
 */
docphoto.editor.countWords = function(text) {
  var normalized = text.replace(/\s/g, ' ');
  var words = normalized.split(' ');
  var count = goog.array.reduce(words, function(acc, word) {
    return word.length > 0 ? acc + 1 : acc;
  }, 0);
  return /** @type {number} */ (count);
};

// remove target=_blank attributes automatically added by salesforce
// to links in their rich text editor
docphoto.removeAnchorTargets = function() {
  goog.array.forEach(goog.dom.getElementsByTagNameAndClass(goog.dom.TagName.A),
                     function(a) {
                       if (a.hasAttribute('target')) {
                         a.removeAttribute('target');
                       }
                     });
};

/**
 * Note: Overriding to render just the image when dragging
 *
 * @param {Element} sourceEl Element to copy.
 * @return {Element} The clone of {@code sourceEl}.
 * @private
 * @notypecheck
 */

// this may help alleviate the ie problems
docphoto.DragListGroup.prototype.cloneNode_ = function(sourceEl) {
  var el = /** @type {Element} */ docphoto.findDragElement(sourceEl);
  return el.cloneNode(false);
};


goog.exportSymbol('docphoto.Uploader', docphoto.Uploader);
goog.exportSymbol('docphoto.editor.triggerEditors',
                  docphoto.editor.triggerEditors);
goog.exportSymbol('docphoto.removeAnchorTargets',
                  docphoto.removeAnchorTargets);
