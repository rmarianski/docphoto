goog.provide('docphoto');

goog.require('goog.dom');
goog.require('goog.dom.TagName');
goog.require('goog.dom.classes');
goog.require('goog.events');
goog.require('goog.events.EventType');
goog.require('goog.net.EventType');
goog.require('goog.net.XhrIo');

/**
 * @param containerId {!string}
 * @param pickFilesId {!string}
 * @param uploadId {!string}
 * @param filesListId {!string}
 * @param options {Object}
 * @constructor
 */
docphoto.Uploader = function(containerId, pickFilesId, uploadId,
                      filesListId, imagesId, options) {
  var defaultOptions = {
    'runtimes': 'gears,html5,flash,silverlight,browserplus',
    'browse_button': pickFilesId,
    'container': containerId,
    'max_file_size': '10mb',
    'flash_swf_url': '/public/plupload/js/plupload.flash.swf',
    'silverlight_xap_url': '/public/plupload/js/plupload.silverlight.xap',
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

  this.uploader.bind('Init', goog.bind(this.onInit, this));
  this.uploader.init();
  this.uploader.bind('FilesAdded', goog.bind(this.onFilesAdded, this));
  this.uploader.bind('UploadProgress', goog.bind(this.onUploadProgress, this));
  this.uploader.bind('Error', goog.bind(this.onUploadError, this));
  this.uploader.bind('FileUploaded', goog.bind(this.onUploadDone, this));
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
 * @param {object} file
 * @param {string} string
 */
docphoto.Uploader.prototype.updateFilePercentage = function(file, string) {
  var node = goog.dom.getElement(file.id);
  var percent = goog.dom.getElementByClass('percent', node);
  percent.innerHTML = string;
};

/**
 * @param {Object} up
 * @param {Object} file
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
  var target = event.target;
  if (goog.dom.classes.has(target, 'image-delete')) {
    event.preventDefault();
    var el = target;
    while (el !== null) {
      if (el.nodeName === goog.dom.TagName.IMG) {
        var imageId = this.parseImageId_(el);
        var xhr = new goog.net.XhrIo();
        goog.events.listen(xhr, goog.net.EventType.SUCCESS,
                           goog.bind(this.handleImageDeleted_, this,
                                     el, xhr),
                           false, this);
        xhr.send('/image/' + imageId + '/delete', "POST");
        break;
      }
      el = goog.dom.getPreviousElementSibling(el);
    }
  }
};

/**
 * @param {!Element} imageEl
 */
docphoto.Uploader.prototype.parseImageId_ = function(imageEl) {
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
  var li = imageElement.parentNode.parentNode;
  goog.dom.removeNode(li);
  xhr.dispose();
};

goog.exportSymbol('docphoto.Uploader', docphoto.Uploader);
