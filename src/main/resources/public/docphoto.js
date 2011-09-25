goog.provide('docphoto');

goog.require('goog.dom');
goog.require('goog.dom.TagName');
goog.require('goog.events');
goog.require('goog.events.EventType');

/**
 * @param containerId {!string}
 * @param pickFilesId {!string}
 * @param uploadId {!string}
 * @param filesListId {!string}
 * @param options {Object}
 * @constructor
 */
docphoto.Uploader = function(containerId, pickFilesId, uploadId, filesListId,
                      options) {
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

  var uploadLink = goog.dom.getElement(uploadId);
  goog.events.listen(uploadLink, goog.events.EventType.CLICK,
                     this.onUpload, false, this);

  this.uploader.init();
  this.uploader.bind('FilesAdded', goog.bind(this.onFilesAdded, this));
  this.uploader.bind('UploadProgress', goog.bind(this.onUploadProgress, this));
  this.uploader.bind('Error', goog.bind(this.onUploadError, this));
  this.uploader.bind('FileUploaded', goog.bind(this.onUploadDone, this));
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
 */
docphoto.Uploader.prototype.onUploadDone = function(up, file) {
  this.updateFilePercentage(file, 'Success');
};

/**
 * @param {Object} up
 * @param {Object} error
 */
docphoto.Uploader.prototype.onUploadError = function(up, error) {
  var file = error.file;
  var msg = 'Error: ' + err.code + ' - ' + err.message;
  if (goog.isDefAndNotNull(file)) {
    this.updateFilePercentage(file, msg);
  } else {
    var node = goog.dom.createDom('p', undefined, msg);
    goog.dom.appendChild(this.filesList, node);
  }
};

goog.exportSymbol('docphoto.Uploader', docphoto.Uploader);
