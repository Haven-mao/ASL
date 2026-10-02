import importlib.util
import pathlib
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('asl_android',pathlib.Path(__file__).parents[1]/'guest/asl_android.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class McpTests(unittest.TestCase):
    def call(self,name,args):
        return module.handle({'jsonrpc':'2.0','id':7,'method':'tools/call','params':{'name':name,'arguments':args}})['result']

    def test_image_coordinates_keep_snapshot_identity(self):
        arguments={'x':100,'y':80,'screenshot_id':'capture-1','expected_package':'com.example.app'}
        with patch.object(module,'call',return_value={'ok':True}) as remote:
            result=self.call('android_tap',arguments)
        self.assertNotIn('isError',result);remote.assert_called_once_with('tap',arguments)

    def test_node_action_cannot_be_overridden(self):
        with patch.object(module,'call',return_value={'ok':True}) as remote:
            self.call('android_set_text',{'node_id':'n1','text':'中文'})
        remote.assert_called_once_with('node',{'node_id':'n1','text':'中文','action':'set_text'})
        with patch.object(module,'call') as remote:
            invalid=self.call('android_click',{'node_id':'n1','action':'set_text'})
        self.assertTrue(invalid['isError']);remote.assert_not_called()

    def test_missing_required_argument_never_reaches_android(self):
        with patch.object(module,'call') as remote:result=self.call('android_set_text',{'text':'hello'})
        self.assertTrue(result['isError']);remote.assert_not_called()

    def test_background_result_is_notification_only(self):
        with patch.object(module,'call',return_value={'notified':True}) as remote:
            self.call('android_finish_task',{'title':'搜索完成','summary':'已找到结果'})
        remote.assert_called_once_with('background/finish',{'title':'搜索完成','summary':'已找到结果'})
        names={tool['name'] for tool in module.schemas()}
        self.assertNotIn('android_handoff',names)

    def test_screenshot_includes_id_for_automatic_conversion(self):
        with patch.object(module,'call',return_value=(b'png',{'X-ASL-Screenshot-Id':'image-1','X-ASL-Image-Width':'1600','X-ASL-Screen-Width':'3200'})):
            result=self.call('android_screenshot',{})
        import json
        metadata=json.loads(result['content'][0]['text'])
        self.assertEqual(metadata['screenshot_id'],'image-1')
        self.assertEqual(result['content'][1]['type'],'image')


if __name__=='__main__':unittest.main()
