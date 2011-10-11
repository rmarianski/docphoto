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
goog.require('goog.net.EventType');
goog.require('goog.net.XhrIo');

goog.require('goog.editor.Command');
goog.require('goog.editor.Field');
goog.require('goog.editor.SeamlessField');
goog.require('goog.editor.plugins.BasicTextFormatter');
goog.require('goog.editor.plugins.EnterHandler');
goog.require('goog.editor.plugins.HeaderFormatter');
goog.require('goog.editor.plugins.LinkBubble');
goog.require('goog.editor.plugins.LinkDialogPlugin');
goog.require('goog.editor.plugins.ListTabHandler');
goog.require('goog.editor.plugins.LoremIpsum');
goog.require('goog.editor.plugins.RemoveFormatting');
goog.require('goog.editor.plugins.SpacesTabHandler');
goog.require('goog.editor.plugins.UndoRedo');
goog.require('goog.ui.editor.DefaultToolbar');
goog.require('goog.ui.editor.ToolbarController');

// to make google advanced mode happy
goog.require('goog.debug.ErrorHandler');
goog.require('goog.Uri');

/**
 * @param {!string} containerId
 * @param {!string} pickFilesId
 * @param {!string} uploadId
 * @param {!string} filesListId
 * @param {!string} imagesId
 * @param {Object} options
 * @constructor
 */
docphoto.Uploader = function(containerId, pickFilesId, uploadId,
                      filesListId, imagesId, options) {
  var defaultOptions = {
    'runtimes': 'gears,html5,flash,silverlight,browserplus',
    'browse_button': pickFilesId,
    'container': containerId,
    'max_file_size': '10mb',
    'flash_swf_url': '/public/js/plupload/js/plupload.flash.swf',
    'silverlight_xap_url': '/public/js/plupload/js/plupload.silverlight.xap',
    'filters': [
      {'title': "Image files", 'extensions': "jpg,gif,png"}
    ]
  };
  var uploadOptions = {};
  if (goog.isObject(options)) {
    goog.object.extend(uploadOptions, defaultOptions, options);
  }
  this.uploader = new plupload.Uploader(uploadOptions);

  this.filesList = goog.dom.getElement(filesListId);
  var imagesDiv = goog.dom.getElement(imagesId);
  this.images = goog.dom.getElementsByTagNameAndClass('ul',
                                                      undefined,
                                                      imagesDiv)[0];

  var uploadLink = goog.dom.getElement(uploadId);
  goog.events.listen(uploadLink, goog.events.EventType.CLICK,
                     this.onUpload, false, this);

  goog.events.listen(this.images, goog.events.EventType.CLICK,
                     this.onImageDelete, false, this);

  this.dlg = null;
  this.initializeDragDrop();

  // the dragger fights with clicking on the textarea
  // rig the fight through event capturing
  goog.events.listen(this.images, goog.events.EventType.CLICK,
                     this.focusTextAreaOnClick, true, this);

  this.uploader.bind('Init', goog.bind(this.onInit, this));
  this.uploader.init();
  this.uploader.bind('FilesAdded', goog.bind(this.onFilesAdded, this));
  this.uploader.bind('UploadProgress', goog.bind(this.onUploadProgress, this));
  this.uploader.bind('Error', goog.bind(this.onUploadError, this));
  this.uploader.bind('FileUploaded', goog.bind(this.onUploadDone, this));
};

docphoto.Uploader.prototype.initializeDragDrop = function() {
  if (goog.isDefAndNotNull(this.dlg)) {
    this.dlg.dispose();
  }
  this.dlg = new goog.fx.DragListGroup();
  this.dlg.addDragList(this.images, goog.fx.DragListDirection.DOWN);
  this.dlg.setHysteresis(30);
  goog.events.listen(this.dlg, goog.fx.DragListGroup.EventType.DRAGEND,
                     this.onDrag, false, this);
  this.dlg.init();
};


/**
 * @param {!Event} event
 */
docphoto.Uploader.prototype.focusTextAreaOnClick = function(event) {
  var target = /** @type {!Element} */ event.target;
  if (target.nodeName === goog.dom.TagName.TEXTAREA) {
    event.preventDefault();
    target.focus();
  }
};

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
  this.uploader.start();
};

/**
 * @param {Object} up
 * @param {Array.<Object>} files
 */
docphoto.Uploader.prototype.onFilesAdded = function(up, files) {
  var filesList = this.filesList;
  goog.array.forEach(files, function(file) {
    var name = goog.dom.createDom('span', undefined, file.name);
    var percent = goog.dom.createDom('span', {'class': 'percent'}, '0%');
    var node = goog.dom.createDom('p', {'id': file.id}, name, ' - ', percent);
    goog.dom.appendChild(filesList, node);
  });
};

/**
 * @param {Object} file
 * @param {string} string
 */
docphoto.Uploader.prototype.updateFilePercentage = function(file, string) {
  var node = goog.dom.getElement(file.id);
  var percent = goog.dom.getElementByClass('percent', node);
  percent.innerHTML = string;
};

/**
 * @param {Object} up
 * @param {{percent: string}} file
 */
docphoto.Uploader.prototype.onUploadProgress = function(up, file) {
  this.updateFilePercentage(file, file.percent + '%');
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

  this.updateFilePercentage(file, 'Success');

  this.initializeDragDrop();
};

/**
 * @param {Object} up
 * @param {Object} error
 */
docphoto.Uploader.prototype.onUploadError = function(up, error) {
  var file = error.file;
  var msg = 'Error: ' + error.code + ' - ' + error.message;
  if (goog.isDefAndNotNull(file)) {
    this.updateFilePercentage(file, msg);
  } else {
    var node = goog.dom.createDom('p', undefined, msg);
    goog.dom.appendChild(this.filesList, node);
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
          var imageId = docphoto.parseImageId_(image);
          var xhr = new goog.net.XhrIo();
          goog.events.listen(xhr, goog.net.EventType.SUCCESS,
                             goog.bind(this.handleImageDeleted_, this,
                                       image, xhr),
                             false, this);
          xhr.send('/image/' + imageId + '/delete', "POST");
        }
        break;
      }
      el = goog.dom.getPreviousElementSibling(el);
    }
  }
};

/**
 * @param {!Element} imageEl
 */
docphoto.parseImageId_ = function(imageEl) {
  var src = imageEl.getAttribute('src');
  var fields = src.split('/');
  var imageId = fields[2];
  return imageId;
};

/**
 * @param {!Element} imageElement
 * @param {!Object} xhr
 */
docphoto.Uploader.prototype.handleImageDeleted_ = function(imageElement, xhr) {
  var li = imageElement.parentNode.parentNode.parentNode;
  goog.dom.removeNode(li);
  xhr.dispose();
};

/**
 * @param {!Event} event
 */
docphoto.Uploader.prototype.onDrag = function(event) {
  var ids = goog.array.map(goog.dom.getChildren(this.images),
                           function(li) {
                             var imageEl = goog.dom.getElementsByTagNameAndClass(
                               goog.dom.TagName.IMG, undefined, li)[0];
                             return docphoto.parseImageId_(imageEl);
                           });
  var paramString = 'order=' + ids.join(',');
  goog.net.XhrIo.send('/reorder-images',
                      goog.nullFunction,
                      'POST',
                      paramString);
};

docphoto.editor.triggerEditors = function() {
  var textareas = goog.dom.getElementsByTagNameAndClass(
    goog.dom.TagName.TEXTAREA, 'editor');
  goog.array.forEach(textareas, docphoto.editor.makeEditor);
};

/**
 * @param {!Element} textarea
 */
docphoto.editor.makeEditor = function(textarea) {
  var id = textarea.getAttribute('id');
  var field = new goog.editor.Field(id);

  field.registerPlugin(new goog.editor.plugins.BasicTextFormatter());
  field.registerPlugin(new goog.editor.plugins.RemoveFormatting());
  field.registerPlugin(new goog.editor.plugins.UndoRedo());
  field.registerPlugin(new goog.editor.plugins.ListTabHandler());
  field.registerPlugin(new goog.editor.plugins.SpacesTabHandler());
  field.registerPlugin(new goog.editor.plugins.EnterHandler());
  field.registerPlugin(new goog.editor.plugins.HeaderFormatter());
  field.registerPlugin(
      new goog.editor.plugins.LoremIpsum('Click here to edit'));
  field.registerPlugin(
      new goog.editor.plugins.LinkDialogPlugin());
  field.registerPlugin(new goog.editor.plugins.LinkBubble());

  // Specify the buttons to add to the toolbar, using built in default buttons.
  var buttons = [
    goog.editor.Command.BOLD,
    goog.editor.Command.ITALIC,
    goog.editor.Command.UNDERLINE,
    goog.editor.Command.FONT_COLOR,
    goog.editor.Command.BACKGROUND_COLOR,
    goog.editor.Command.FONT_FACE,
    goog.editor.Command.FONT_SIZE,
    goog.editor.Command.LINK,
    goog.editor.Command.UNDO,
    goog.editor.Command.REDO,
    goog.editor.Command.UNORDERED_LIST,
    goog.editor.Command.ORDERED_LIST,
    goog.editor.Command.INDENT,
    goog.editor.Command.OUTDENT,
    goog.editor.Command.JUSTIFY_LEFT,
    goog.editor.Command.JUSTIFY_CENTER,
    goog.editor.Command.JUSTIFY_RIGHT,
    goog.editor.Command.SUBSCRIPT,
    goog.editor.Command.SUPERSCRIPT,
    goog.editor.Command.STRIKE_THROUGH,
    goog.editor.Command.REMOVE_FORMAT
  ];

  var toolbarElement = goog.dom.createDom(goog.dom.TagName.DIV,
                                          {"id": id + '-toolbar'});
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

  goog.events.listen(field, goog.editor.Field.EventType.DELAYEDCHANGE,
                     goog.partial(docphoto.editor.updateFieldContents_,
                                  dataField, field));

  field.makeEditable();
};

/**
 * @param {!Element} dataField
 * @param {!Object} editorField
 */
docphoto.editor.updateFieldContents_ = function(dataField, editorField) {
  dataField.value = editorField.getCleanContents();
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

goog.exportSymbol('docphoto.Uploader', docphoto.Uploader);
goog.exportSymbol('docphoto.editor.triggerEditors',
                  docphoto.editor.triggerEditors);
goog.exportSymbol('docphoto.removeAnchorTargets',
                  docphoto.removeAnchorTargets);
