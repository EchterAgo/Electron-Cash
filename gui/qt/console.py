
# source: http://stackoverflow.com/questions/2758159/how-to-embed-a-python-interpreter-in-a-pyqt-widget

import sys
import os
import re
import traceback
import platform

from PyQt5 import QtCore
from PyQt5 import QtGui
from PyQt5 import QtWidgets

from electroncash import util, get_config
from electroncash.i18n import _
from .util import ColorScheme, MONOSPACE_FONT


class ConsoleTextEdit(QtWidgets.QPlainTextEdit):
    def __init__(self, parent=None):
        super().__init__(parent)

    def keyPressEvent(self, event: QtGui.QKeyEvent):
        # Let the warning overlay process key presses when active
        if self.parent().warningOverlay:
            self.parent().warningOverlay.keyPressEvent(event)
            return

        if event.key() == QtCore.Qt.Key_Tab or event.key() == QtCore.Qt.Key_Backtab:
            if (event.modifiers() & QtCore.Qt.ControlModifier) == QtCore.Qt.ControlModifier:
                # Ctrl + Tab / Ctrl + Shift + Tab for navigating the tab control, just let the parent handle it
                pass
            elif (event.modifiers() & QtCore.Qt.ShiftModifier) == QtCore.Qt.ShiftModifier:
                # Shift + Tab, give focus to previous widget
                self.parent().focusPreviousChild()
                return
            else:
                # No Ctrl / Shift pressed, show completions
                self.parent().completions()
                return

        closed_completions = self.parent().hide_completions()

        if event.key() in (QtCore.Qt.Key_Enter, QtCore.Qt.Key_Return):
            self.parent().runCommand()
            return
        if event.key() == QtCore.Qt.Key_Home:
            self.parent().setCursorPosition(0)
            return
        if event.key() == QtCore.Qt.Key_PageUp:
            return
        elif event.key() in (QtCore.Qt.Key_Left, QtCore.Qt.Key_Backspace):
            if self.parent().getCursorPosition() == 0:
                return
        elif event.key() == QtCore.Qt.Key_Up:
            self.parent().setCommand(self.parent().getPrevHistoryEntry())
            return
        elif event.key() == QtCore.Qt.Key_Down:
            self.parent().setCommand(self.parent().getNextHistoryEntry())
            return
        elif event.key() == QtCore.Qt.Key_L and event.modifiers() == QtCore.Qt.ControlModifier:
            self.parent().clear()
        elif event.key() == QtCore.Qt.Key_Insert and event.modifiers() == QtCore.Qt.NoModifier:
            self.setOverwriteMode(not self.overwriteMode())

        super(ConsoleTextEdit, self).keyPressEvent(event)

class Console(QtWidgets.QWidget):

    def __init__(self, wallet, prompt='>> ', startup_message='', parent=None):
        super().__init__(parent)

        self.prompt = prompt
        self.history = []
        self.namespace = {}
        self.construct = []

        self.setGeometry(50, 75, 600, 400)

        self.editor = ConsoleTextEdit(self)
        self.editor.resize(self.size())
        self.editor.setWordWrapMode(QtGui.QTextOption.WrapAnywhere)
        self.editor.setUndoRedoEnabled(False)
        self.editor.setFont(QtGui.QFont(MONOSPACE_FONT, 10, QtGui.QFont.Normal))

        self.showMessage(startup_message)

        self.updateNamespace({'run':self.run_script})
        self.set_json(False)

        self.warningOverlay = None

        wallet_storage = wallet.storage

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self.editor.resize(self.size())
        if self.warningOverlay:
            self.warningOverlay.resize(self.size())

    def set_json(self, b):
        self.is_json = b

    def run_script(self, filename):
        with open(filename) as f:
            script = f.read()

        # eval is generally considered bad practice. use it wisely!
        result = eval(script, self.namespace, self.namespace)

    def updateNamespace(self, namespace):
        self.namespace.update(namespace)

    def showMessage(self, message):
        self.editor.appendPlainText(message)
        self.newPrompt()

    def clear(self):
        self.editor.setPlainText('')
        self.newPrompt()

    def newPrompt(self):
        if self.construct:
            prompt = '.' * len(self.prompt)
        else:
            prompt = self.prompt

        self.completions_pos = self.editor.textCursor().position()
        self.completions_visible = False

        self.editor.appendPlainText(prompt)
        self.editor.moveCursor(QtGui.QTextCursor.End)

    def getCommand(self):
        doc = self.editor.document()
        curr_line = doc.findBlockByLineNumber(doc.lineCount() - 1).text()
        curr_line = curr_line.rstrip()
        curr_line = curr_line[len(self.prompt):]
        return curr_line

    def setCommand(self, command):
        if self.getCommand() == command:
            return

        doc = self.editor.document()
        curr_line = doc.findBlockByLineNumber(doc.lineCount() - 1).text()
        self.editor.moveCursor(QtGui.QTextCursor.End)
        for i in range(len(curr_line) - len(self.prompt)):
            self.editor.moveCursor(QtGui.QTextCursor.Left, QtGui.QTextCursor.KeepAnchor)

        self.editor.textCursor().removeSelectedText()
        self.editor.textCursor().insertText(command)
        self.editor.moveCursor(QtGui.QTextCursor.End)

    def show_completions(self, completions):
        if self.completions_visible:
            self.hide_completions()

        c = self.editor.textCursor()
        c.setPosition(self.completions_pos)

        completions = map(lambda x: x.split('.')[-1], completions)
        t = '\n' + ' '.join(completions)
        if len(t) > 500:
            t = t[:500] + '...'
        c.insertText(t)
        self.completions_end = c.position()

        self.editor.moveCursor(QtGui.QTextCursor.End)
        self.completions_visible = True

    def hide_completions(self) -> bool:
        if not self.completions_visible:
            return False
        c = self.editor.textCursor()
        c.setPosition(self.completions_pos)
        l = self.completions_end - self.completions_pos
        for x in range(l): c.deleteChar()

        self.editor.moveCursor(QtGui.QTextCursor.End)
        self.completions_visible = False
        return True

    def getConstruct(self, command):
        if self.construct:
            prev_command = self.construct[-1]
            self.construct.append(command)
            if not prev_command and not command:
                ret_val = '\n'.join(self.construct)
                self.construct = []
                return ret_val
            else:
                return ''
        else:
            if command and command[-1] == (':'):
                self.construct.append(command)
                return ''
            else:
                return command

    def getHistory(self):
        return self.history

    def setHisory(self, history):
        self.history = history

    def addToHistory(self, command):
        if command[0:1] == ' ':
            return

        if command and (not self.history or self.history[-1] != command):
            self.history.append(command)
        self.history_index = len(self.history)

    def getPrevHistoryEntry(self):
        if self.history:
            self.history_index = max(0, self.history_index - 1)
            return self.history[self.history_index]
        return ''

    def getNextHistoryEntry(self):
        if self.history:
            hist_len = len(self.history)
            self.history_index = min(hist_len, self.history_index + 1)
            if self.history_index < hist_len:
                return self.history[self.history_index]
        return ''

    def getCursorPosition(self):
        c = self.editor.textCursor()
        return c.position() - c.block().position() - len(self.prompt)

    def setCursorPosition(self, position):
        self.editor.moveCursor(QtGui.QTextCursor.StartOfLine)
        for i in range(len(self.prompt) + position):
            self.editor.moveCursor(QtGui.QTextCursor.Right)

    def register_command(self, c, func):
        methods = { c: func}
        self.updateNamespace(methods)


    def runCommand(self):
        command = self.getCommand()
        self.addToHistory(command)

        command = self.getConstruct(command)

        if command:
            tmp_stdout = sys.stdout

            class stdoutProxy():
                def __init__(self, write_func):
                    self.write_func = write_func
                    self.skip = False

                def flush(self):
                    pass

                def write(self, text):
                    if not self.skip:
                        stripped_text = text.rstrip('\n')
                        self.write_func(stripped_text)
                        QtCore.QCoreApplication.processEvents()
                    self.skip = not self.skip

            if type(self.namespace.get(command)) == type(lambda:None):
                self.editor.appendPlainText("'{}' is a function. Type '{}()' to use it in the Python console."
                                     .format(command, command))
                self.newPrompt()
                return

            sys.stdout = stdoutProxy(self.editor.appendPlainText)
            try:
                try:
                    # eval is generally considered bad practice. use it wisely!
                    result = eval(command, self.namespace, self.namespace)
                    if result is not None:
                        if self.is_json:
                            util.print_msg(util.json_encode(result))
                        else:
                            self.editor.appendPlainText(repr(result))
                except SyntaxError:
                    # exec is generally considered bad practice. use it wisely!
                    exec(command, self.namespace, self.namespace)
            except SystemExit:
                self.close()
            except (Exception, BaseException):
                # Catch errors in the network layer as well, as long as it uses BaseException.
                traceback_lines = traceback.format_exc().split('\n')
                # Remove traceback mentioning this file, and a linebreak
                for i in (3,2,1,-1):
                    traceback_lines.pop(i)
                self.editor.appendPlainText('\n'.join(traceback_lines))
            sys.stdout = tmp_stdout
        self.newPrompt()
        self.set_json(False)

    def completions(self):
        cmd = self.getCommand()
        lastword = re.split(' |\(|\)',cmd)[-1]
        beginning = cmd[0:-len(lastword)]

        path = lastword.split('.')
        ns = self.namespace.keys()

        if len(path) == 1:
            ns = ns
            prefix = ''
        else:
            obj = self.namespace.get(path[0])
            prefix = path[0] + '.'
            ns = dir(obj)


        completions = []
        for x in ns:
            if x[0] == '_':continue
            xx = prefix + x
            if xx.startswith(lastword):
                completions.append(xx)
        completions.sort()

        if not completions:
            self.hide_completions()
        elif len(completions) == 1:
            self.hide_completions()
            self.setCommand(beginning + completions[0])
        else:
            # find common prefix
            p = os.path.commonprefix(completions)
            if len(p)>len(lastword):
                self.hide_completions()
                self.setCommand(beginning + p)
            else:
                self.show_completions(completions)


welcome_message = '''
   ---------------------------------------------------------------
     Welcome to a primitive Python interpreter.
   ---------------------------------------------------------------
'''

if __name__ == '__main__':
    app = QtWidgets.QApplication(sys.argv)
    console = Console(startup_message=welcome_message)
    console.updateNamespace({'myVar1' : app, 'myVar2' : 1234})
    console.show()
    sys.exit(app.exec_())
